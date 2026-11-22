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

#include "AnrDumper.h"

#include <dirent.h>
#include <pthread.h>
#include <cxxabi.h>
#include <unistd.h>
#include <syscall.h>
#include <cstdlib>
#include <cstring>

#include <optional>
#include <cinttypes>
#include <fstream>
#include <iostream>
#include <iosfwd>
#include <string>
#include <fcntl.h>

#include "MatrixTracer.h"
#include "Support.h"

#define SIGNAL_CATCHER_THREAD_NAME "Signal Catcher"
#define SIGNAL_CATCHER_THREAD_SIGBLK 0x1000
#define O_WRONLY 00000001
#define O_CREAT 00000100
#define O_TRUNC 00001000

namespace MatrixTracer {
/** Matrix debugger 原有的两秒等待。 */
static const int NATIVE_DUMP_TIMEOUT = 2; // 2 seconds
/** Matrix 保存的调用线程原信号掩码。 */
static sigset_t old_sigSet;
/** Matrix 外部 ANR trace 文件路径。 */
const char* mAnrTraceFile;
/** Matrix 手动 trace 文件路径。 */
const char* mPrintTraceFile;

/** 保存 Matrix trace 路径并解除当前线程对 SIGQUIT 的屏蔽。 */
AnrDumper::AnrDumper(const char* anrTraceFile, const char* printTraceFile) {
    // must unblock SIGQUIT, otherwise the signal handler can not capture SIGQUIT
    mAnrTraceFile = anrTraceFile;
    mPrintTraceFile = printTraceFile;
    /** 需要解除屏蔽的 SIGQUIT 信号集合。 */
    sigset_t sigSet;
    sigemptyset(&sigSet);
    sigaddset(&sigSet, SIGQUIT);
    pthread_sigmask(SIG_UNBLOCK, &sigSet , &old_sigSet);
}

/** 按 Matrix 原逻辑查找 Signal Catcher 线程。 */
static int getSignalCatcherThreadId() {
    /** 当前进程线程目录。 */
    char taskDirPath[128];
    /** 当前进程线程目录句柄。 */
    DIR *taskDir;
    /** Matrix 原有的线程信号屏蔽值。 */
    long long sigblk;
    /** 匹配信号屏蔽值的 Signal Catcher 线程。 */
    int signalCatcherTid = -1;
    /** 按线程名首次找到的 Signal Catcher。 */
    int firstSignalCatcherTid = -1;

    snprintf(taskDirPath, sizeof(taskDirPath), "/proc/%d/task", getpid());
    if ((taskDir = opendir(taskDirPath)) == nullptr) {
        return -1;
    }
    /** 线程目录条目。 */
    struct dirent *dent;
    /** 当前目录条目对应的线程号。 */
    pid_t tid;
    while ((dent = readdir(taskDir)) != nullptr) {
        tid = atoi(dent->d_name);
        if (tid <= 0) {
            continue;
        }

        /** 当前线程名称。 */
        char threadName[1024];
        /** 当前线程 comm 文件路径。 */
        char commFilePath[1024];
        snprintf(commFilePath, sizeof(commFilePath), "/proc/%d/task/%d/comm", getpid(), tid);

        Support::readFileAsString(commFilePath, threadName, sizeof(threadName));

        if (strncmp(SIGNAL_CATCHER_THREAD_NAME, threadName , sizeof(SIGNAL_CATCHER_THREAD_NAME)-1) != 0) {
            continue;
        }

        if (firstSignalCatcherTid == -1) {
            firstSignalCatcherTid = tid;
        }

        sigblk = 0;
        /** 当前线程状态文件路径。 */
        char taskPath[128];
        snprintf(taskPath, sizeof(taskPath), "/proc/%d/status", tid);

        /** Matrix 原有状态文件描述符包装。 */
        ScopedFileDescriptor fd(open(taskPath, O_RDONLY, 0));
        /** Matrix 原有逐行读取器。 */
        LineReader lr(fd.get());
        /** 当前状态文本行。 */
        const char *line;
        /** 当前状态文本行长度。 */
        size_t len;
        while (lr.getNextLine(&line, &len)) {
            if (1 == sscanf(line, "SigBlk: %" SCNx64, &sigblk)) {
                break;
            }
            lr.popLine(len);
        }
        if (SIGNAL_CATCHER_THREAD_SIGBLK != sigblk) {
            continue;
        }
        signalCatcherTid = tid;
        break;
    }
    closedir(taskDir);

    if (signalCatcherTid == -1) {
        signalCatcherTid = firstSignalCatcherTid;
    }
    return signalCatcherTid;
}

/** 向 Matrix 查找到的 Signal Catcher 转交 SIGQUIT。 */
static void sendSigToSignalCatcher() {
    /** Matrix 原逻辑查找到的目标线程。 */
    int tid = getSignalCatcherThreadId();
    syscall(SYS_tgkill, getpid(), tid, SIGQUIT);
}

/** 先执行 Matrix Java 采样，再安装 hook 并转交信号。 */
static void *anrCallback(void* arg) {
    anrDumpCallback();
    if (strlen(mAnrTraceFile) > 0) {
        hookAnrTraceWrite(false);
    }
    sendSigToSignalCatcher();
    return nullptr;
}

/** 按 Matrix 原手动 trace 路径安装 hook 并转交信号。 */
static void *siUserCallback(void* arg) {
    if (strlen(mPrintTraceFile) > 0) {
        hookAnrTraceWrite(true);
    }

    sendSigToSignalCatcher();
    return nullptr;
}

/** 按 Matrix 原顺序回调、恢复并重新排队 debugger 信号。 */
void* AnrDumper::nativeBacktraceCallback(void* arg) {
    nativeBacktraceDumpCallback();
    restoreNativeBacktraceHandlersLocked();
    /** Matrix 转交 debugger 信号附带的值。 */
    sigval val;
    val.sival_int = 1;

    /** Matrix 构造的 debugger 信号信息。 */
    siginfo_t info;
    memset(&info, 0, sizeof(siginfo_t));
    info.si_signo = BIONIC_SIGNAL_DEBUGGER;
    info.si_code = SI_QUEUE;
    info.si_pid = getpid();
    info.si_uid = getuid();
    info.si_value = val;
    syscall(SYS_rt_sigqueueinfo, getpid(), BIONIC_SIGNAL_DEBUGGER, &info);
    sleep(NATIVE_DUMP_TIMEOUT);
    installNativeBacktraceHandlersLocked();
    return nullptr;
}

/** 按 Matrix 原私有信号字段区分外部和自身来源。 */
void AnrDumper::handleSignal(int sig, const siginfo_t *info, void *uc) {
    /** Matrix 从信号私有字段读取的第一个来源值。 */
    int fromPid1 = info->_si_pad[3];
    /** Matrix 从信号私有字段读取的第二个来源值。 */
    int fromPid2 = info->_si_pad[4];
    /** 当前进程号。 */
    int myPid = getpid();
    /** Matrix 原有的自身信号判断结果。 */
    bool fromMySelf = fromPid1 == myPid || fromPid2 == myPid;
    if (sig == SIGQUIT) {
        /** Matrix 原有的信号处理工作线程。 */
        pthread_t thd;
        if (!fromMySelf) {
            pthread_create(&thd, nullptr, anrCallback, nullptr);
        } else {
            pthread_create(&thd, nullptr, siUserCallback, nullptr);
        }
        pthread_detach(thd);
    }
}


/** 按 Matrix 原来源判断处理 debugger 信号。 */
void AnrDumper::handleDebuggerSignal(int sig, const siginfo_t *info, void *uc) {
    if (sig == BIONIC_SIGNAL_DEBUGGER) {
        /** Matrix 从信号私有字段读取的第一个来源值。 */
        int fromPid1 = info->_si_pad[3];
        /** Matrix 从信号私有字段读取的第二个来源值。 */
        int fromPid2 = info->_si_pad[4];
        /** 当前进程号。 */
        int myPid = getpid();
        /** Matrix 原有的自身信号判断结果。 */
        bool fromMySelf = fromPid1 == myPid || fromPid2 == myPid;
        if (!fromMySelf) {
            /** Matrix 原有的信号处理工作线程。 */
            pthread_t thd;
            pthread_create(&thd, nullptr, nativeBacktraceCallback, nullptr);
            pthread_detach(thd);
        }
    }
}

/** 转发 Matrix ANR trace 回调。 */
static void *anr_trace_callback(void* args) {
    anrDumpTraceCallback();
    return nullptr;
}

/** 转发 Matrix 手动 trace 回调。 */
static void *print_trace_callback(void* args) {
    printTraceCallback();
    return nullptr;
}


/** 按 Matrix 原逻辑恢复线程信号掩码。 */
AnrDumper::~AnrDumper() {
    pthread_sigmask(SIG_SETMASK, &old_sigSet, nullptr);
}

}   // namespace MatrixTracer
