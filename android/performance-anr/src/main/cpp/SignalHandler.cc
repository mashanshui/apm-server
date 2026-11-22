/*
 * Tencent is pleased to support the open source community by making wechat-matrix available.
 * Copyright (C) 2021 THL A29 Limited, a Tencent company. All rights reserved.
 * Licensed under the BSD 3-Clause License (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://opensource.org/licenses/BSD-3-Clause
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

// Author: leafjia@tencent.com
//
// SignalHandler.cpp

#include "SignalHandler.h"

#include <malloc.h>
#include <syscall.h>
#include <dirent.h>
#include <unistd.h>

#include <mutex>
#include <vector>
#include <algorithm>
#include <cstring>
#include <cinttypes>

#include "Support.h"

#define SIGNAL_CATCHER_THREAD_NAME "Signal Catcher"
#define SIGNAL_CATCHER_THREAD_SIGBLK 0x1000

namespace MatrixTracer {

/** Matrix 保存的原 SIGQUIT 处理器。 */
struct sigaction sOldHandlers;
/** Matrix 保存的原 debugger 处理器。 */
struct sigaction sNativeBacktraceOldHandlers;
/** SIGQUIT 处理器安装标志。 */
static bool sHandlerInstalled = false;
/** debugger 处理器安装标志。 */
static bool sNativeBacktraceHandlerInstalled = false;

/** Matrix 按原顺序维护的处理器栈。 */
static std::vector<SignalHandler*>* sHandlerStack = nullptr;
/** 保护 SIGQUIT 处理器栈的 Matrix 锁。 */
static std::mutex sHandlerStackMutex;
/** debugger 信号处理路径使用的 Matrix 锁。 */
static std::mutex sNativeBacktraceHandlerStackMutex;
/** 备用信号栈安装标志。 */
static bool sStackInstalled = false;
/** Matrix 保存的原信号栈。 */
static stack_t sOldStack;
/** Matrix 按需分配的备用信号栈。 */
static stack_t sNewStack;

/** 按 Matrix 原逻辑安装备用信号栈。 */
static void installAlternateStackLocked() {
    if (sStackInstalled)
        return;

    memset(&sOldStack, 0, sizeof(sOldStack));
    memset(&sNewStack, 0, sizeof(sNewStack));
    /** Matrix 原有备用栈大小。 */
    static constexpr unsigned kSigStackSize = std::max(16384, SIGSTKSZ);

    if (sigaltstack(nullptr, &sOldStack) == -1 || !sOldStack.ss_sp || sOldStack.ss_size < kSigStackSize) {
        sNewStack.ss_sp = calloc(1, kSigStackSize);
        sNewStack.ss_size = kSigStackSize;
        if (sigaltstack(&sNewStack, nullptr) == -1) {
            free(sNewStack.ss_sp);
            return;
        }
    }

    sStackInstalled = true;
}

/** 保存旧处理器并安装 Matrix SIGQUIT 处理器。 */
bool SignalHandler::installHandlersLocked() {
    if (sHandlerInstalled) {
        return false;
    }

    if (sigaction(TARGET_SIG, nullptr, &sOldHandlers) == -1) {
        return false;
    }

    /** 按 Matrix 原选项构造的信号处理器。 */
    struct sigaction sa{};
    sa.sa_sigaction = signalHandler;
    sa.sa_flags = SA_ONSTACK | SA_SIGINFO | SA_RESTART;

    if (sigaction(TARGET_SIG, &sa, nullptr) == -1) {
        return false;
    }

    sHandlerInstalled = true;
    return true;
}

/** 保存旧处理器并安装 Matrix debugger 信号处理器。 */
bool SignalHandler::installNativeBacktraceHandlersLocked() {
    if (sNativeBacktraceHandlerInstalled) {
        return false;
    }

    if (sigaction(BIONIC_SIGNAL_DEBUGGER, nullptr, &sNativeBacktraceOldHandlers) == -1) {
        return false;
    }

    /** 按 Matrix 原选项构造的信号处理器。 */
    struct sigaction sa{};
    sa.sa_sigaction = debuggerSignalHandler;
    sa.sa_flags = SA_ONSTACK | SA_SIGINFO | SA_RESTART;

    if (sigaction(BIONIC_SIGNAL_DEBUGGER, &sa, nullptr) == -1) {
        return false;
    }

    sNativeBacktraceHandlerInstalled = true;
    return true;
}

/** 按 Matrix 原回退逻辑安装默认处理器。 */
void SignalHandler::installDefaultHandler(int sig) {
    /** Matrix 构造的默认信号处理器。 */
    struct sigaction sa;
    memset(&sa, 0, sizeof(sa));
    sigemptyset(&sa.sa_mask);
    sa.sa_handler = SIG_DFL;
    sa.sa_flags = SA_RESTART;
    sigaction(sig, &sa, nullptr);
}

/** 按 Matrix 原逻辑恢复 SIGQUIT 处理器。 */
void SignalHandler::restoreHandlersLocked() {
    if (!sHandlerInstalled)
        return;

    if (sigaction(TARGET_SIG, &sOldHandlers, nullptr) == -1) {
        installDefaultHandler(TARGET_SIG);
    }

    sHandlerInstalled = false;
}

/** 按 Matrix 原逻辑恢复 debugger 信号处理器。 */
void SignalHandler::restoreNativeBacktraceHandlersLocked() {
    if (!sNativeBacktraceHandlerInstalled)
        return;

    if (sigaction(BIONIC_SIGNAL_DEBUGGER, &sNativeBacktraceOldHandlers, nullptr) == -1) {
        installDefaultHandler(BIONIC_SIGNAL_DEBUGGER);
    }

    sNativeBacktraceHandlerInstalled = false;
}

/** 按 Matrix 原逻辑恢复备用信号栈。 */
static void restoreAlternateStackLocked() {
    if (!sStackInstalled)
        return;

    /** 当前线程已安装的信号栈。 */
    stack_t current_stack;
    if (sigaltstack(nullptr, &current_stack) == -1)
        return;

    if (current_stack.ss_sp == sNewStack.ss_sp) {
        if (sOldStack.ss_sp) {
            if (sigaltstack(&sOldStack, nullptr) == -1)
                return;
        } else {
            /** 用于按 Matrix 原逻辑关闭备用栈的参数。 */
            stack_t disable_stack;
            disable_stack.ss_flags = SS_DISABLE;
            if (sigaltstack(&disable_stack, nullptr) == -1)
                return;
        }
    }

    free(sNewStack.ss_sp);
    sStackInstalled = false;
}

/** 按 Matrix 原顺序向处理器栈分发 SIGQUIT。 */
void SignalHandler::signalHandler(int sig, siginfo_t* info, void* uc) {
    /** Matrix 原有信号分发锁。 */
    std::unique_lock<std::mutex> lock(sHandlerStackMutex);

    for (auto it = sHandlerStack->rbegin(); it != sHandlerStack->rend(); ++it) {
        (*it)->handleSignal(sig, info, uc);
    }

    lock.unlock();
}

/** 按 Matrix 原顺序向处理器栈分发 debugger 信号。 */
void SignalHandler::debuggerSignalHandler(int sig, siginfo_t* info, void* uc) {
    /** Matrix 原有信号分发锁。 */
    std::unique_lock<std::mutex> lock(sNativeBacktraceHandlerStackMutex);

    for (auto it = sHandlerStack->rbegin(); it != sHandlerStack->rend(); ++it) {
        (*it)->handleDebuggerSignal(sig, info, uc);
    }

    lock.unlock();
}

/** 按 Matrix 原顺序安装处理器并登记实例。 */
SignalHandler::SignalHandler() {
    /** Matrix 原有处理器栈锁。 */
    std::lock_guard<std::mutex> lock(sHandlerStackMutex);

    if (!sHandlerStack)
        sHandlerStack = new std::vector<SignalHandler*>;

    installAlternateStackLocked();
    installHandlersLocked();
    installNativeBacktraceHandlersLocked();
    sHandlerStack->push_back(this);
}

/** 按 Matrix 原顺序移除实例并恢复处理器。 */
SignalHandler::~SignalHandler() {
    /** Matrix 原有处理器栈锁。 */
    std::lock_guard<std::mutex> lock(sHandlerStackMutex);

    /** 当前实例在 Matrix 处理器栈中的位置。 */
    auto it = std::find(sHandlerStack->begin(), sHandlerStack->end(), this);
    sHandlerStack->erase(it);
    if (sHandlerStack->empty()) {
        delete sHandlerStack;
        sHandlerStack = nullptr;
        restoreAlternateStackLocked();
        restoreNativeBacktraceHandlersLocked();
        restoreHandlersLocked();
    }
}

}   // namespace MatrixTracer
