#!/usr/bin/env python3
"""对照 Matrix 原版与迁移版的 connect、open、首次 write 代理行为。"""

import argparse
from pathlib import Path
import subprocess
import tempfile
import uuid


def function(source: str, signature: str) -> str:
    """提取顶层 C++ 方法，不改写其代理逻辑。"""
    # 顶层方法的结束花括号单独位于行首。
    start = source.index(signature)
    # 包含闭合花括号，不包括下一个方法。
    end = source.index("\n}\n", start) + len("\n}")
    return source[start:end]


# 将原函数和 ByteHook 原函数链替换为同一可观察桩；只使用安全 NUL 终止样本。
NAMESPACE = r'''
namespace /* NAME */ {
/** 可供测试读取的处理顺序。 */
static std::vector<std::string> events;
/** 模拟的当前线程号。 */
static int currentTid;
/** Matrix 的目标写入线程号。 */
static int signalCatcherTid;
/** Matrix 的写入命中状态。 */
static bool isTraceWrite;
/** Matrix 的手动 trace 状态。 */
static bool fromMyPrintTrace;
/** Matrix 的 hook 活动状态。 */
static bool isHooking;
/** 控制原 write 是否返回失败。 */
static bool failWrite;
/** Matrix 指定的 ANR 文件名。 */
static std::string anrTracePathString;
/** Matrix 指定的手动 trace 文件名。 */
static std::string printTracePathString;

/** 返回模拟的当前线程号。 */
static int gettid() { return currentTid; }
/** 记录原始 connect 调用，不访问真实 socket。 */
static int realConnect(int, const struct sockaddr*, socklen_t) { events.push_back("CONNECT"); return -1; }
/** 记录原始 open 调用，不访问真实文件。 */
static int realOpen(const char*, int, mode_t) { events.push_back("OPEN"); return -1; }
/** 记录原始 write 调用及结果，不写入真实文件。 */
static ssize_t realWrite(int fd, const void*, size_t count) {
    /** 按场景模拟原函数成功或失败。 */
    ssize_t result = failWrite ? -1 : static_cast<ssize_t>(count);
    events.push_back("WRITE:" + std::to_string(fd) + ":" + std::to_string(count) + ":" + std::to_string(result));
    return result;
}
/** 原 connect 的测试替身。 */
static int (*original_connect)(int, const struct sockaddr*, socklen_t) = realConnect;
/** 原 open 的测试替身。 */
static int (*original_open)(const char*, int, mode_t) = realOpen;
/** 原 write 的测试替身。 */
static ssize_t (*original_write)(int, const void*, size_t) = realWrite;
/** 记录 Matrix 转成字符串后的内容及清除活动标志。 */
static void writeAnr(const std::string& content, const std::string& path) {
    isHooking = false;
    events.push_back("SAVE:" + path + ":" + content);
}
/** 记录 Matrix 的 ANR trace 回调。 */
static bool anrDumpTraceCallback() { events.push_back("ANR"); return true; }
/** 记录 Matrix 的手动 trace 回调。 */
static bool printTraceCallback() { events.push_back("MANUAL"); return true; }

/* CONNECT */
/* OPEN */
/* WRITE */

/** 重置模拟状态并运行指定代理样本。 */
static std::vector<std::string> run(int scenario) {
    events.clear();
    currentTid = 1;
    signalCatcherTid = 0;
    isTraceWrite = false;
    fromMyPrintTrace = scenario == 1 || scenario == 4;
    isHooking = true;
    failWrite = false;
    anrTracePathString = "anr";
    printTracePathString = scenario == 4 ? "" : "manual";
    /** 安全终止的文本；前三个字节有意短于完整字符串。 */
    const char first[] = "prefix-after-count";
    /** 用于验证首次命中之后的后续写入。 */
    const char second[] = "next";
    /** 使用与 Android sockaddr 相同的路径字段。 */
    struct sockaddr_un address{};
    std::strncpy(address.sun_path, scenario == 2 ? "/other" : HOOK_CONNECT_PATH, sizeof(address.sun_path) - 1);

    if (scenario == 1) {
        my_open(HOOK_OPEN_PATH, 0, 0);
        currentTid = 2;
        my_write(8, first, 2);
        currentTid = 1;
        failWrite = true;
        my_write(9, first, 3);
    } else {
        my_connect(5, reinterpret_cast<struct sockaddr*>(&address), sizeof(address));
        if (scenario == 2) my_open("/other", 0, 0);
        my_write(8, scenario == 3 ? nullptr : first, scenario == 3 ? 0 : 3);
        if (scenario == 0) my_write(9, second, 4);
    }
    events.push_back("STATE:" + std::to_string(isTraceWrite) + ":" + std::to_string(fromMyPrintTrace) +
                     ":" + std::to_string(isHooking) + ":" + std::to_string(signalCatcherTid));
    return events;
}
}
'''


# ByteHook 宏只在主机对照中转到同一原函数桩；真实框架行为由设备测试验证。
CPP_HARNESS = r'''
#include <sys/types.h>
#include <sys/socket.h>
#include <sys/un.h>
#include <cstring>
#include <iostream>
#include <string>
#include <vector>
#define HOOK_CONNECT_PATH "/dev/socket/tombstoned_java_trace"
#define HOOK_OPEN_PATH "/data/anr/traces.txt"
#define BYTEHOOK_STACK_SCOPE() do {} while (false)
#define previous_my_connect original_connect
#define previous_my_open original_open
#define previous_my_write original_write
#define BYTEHOOK_CALL_PREV(function, ...) previous_ ## function(__VA_ARGS__)
/* ORIGINAL */
/* MIGRATED */

/** 比较原版和迁移版，并核对原版的预期调用次序。 */
int main() {
    /** 五组样本覆盖 ANR、手动、不同线程、目标未命中和空输入。 */
    const std::vector<std::vector<std::string>> expected = {
        {"CONNECT", "SAVE:anr:prefix-after-count", "ANR", "WRITE:8:3:3", "WRITE:9:4:4", "STATE:0:0:0:0"},
        {"OPEN", "WRITE:8:2:2", "SAVE:manual:prefix-after-count", "MANUAL", "WRITE:9:3:-1", "STATE:0:0:0:0"},
        {"CONNECT", "OPEN", "WRITE:8:3:3", "STATE:0:0:1:0"},
        {"CONNECT", "WRITE:8:0:0", "STATE:0:0:1:0"},
        {"CONNECT", "WRITE:8:3:3", "STATE:0:1:1:0"},
    };
    for (int i = 0; i < static_cast<int>(expected.size()); ++i) {
        /** 同一场景中原版和迁移版的完整调用记录。 */
        auto original = Original::run(i);
        auto migrated = Migrated::run(i);
        if (original != migrated || original != expected[i]) {
            std::cerr << "样本 " << i + 1 << " 的代理调用顺序或状态不一致\n";
            return 1;
        }
        std::cout << "样本 " << i + 1 << "：原版与迁移版一致\n";
    }
    return 0;
}
'''


def wrapper(name: str, path: Path) -> str:
    """从指定文件提取三个代理并放入互不干扰的测试命名空间。"""
    # 读取用户指定版本的原始源文件。
    source = path.read_text(encoding="utf-8")
    # 两份源码共用的代理函数签名。
    signatures = {
        "CONNECT": "int my_connect(int __fd, const struct sockaddr* __addr, socklen_t __addr_length) {",
        "OPEN": "int my_open(const char *pathname, int flags, mode_t mode) {",
        "WRITE": "ssize_t my_write(int fd, const void* const buf, size_t count) {",
    }
    # 在临时命名空间内逐项放入原始方法。
    result = NAMESPACE.replace("/* NAME */", name)
    for key, signature in signatures.items():
        result = result.replace("/* " + key + " */", function(source, signature))
    return result


def main() -> None:
    """在临时目录编译运行对照程序，不更改 Matrix 或生产源码。"""
    # 原版 Matrix 路径由调用者显式指定。
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("matrix_source", type=Path, help="Matrix 的 MatrixTracer.cc 路径")
    parser.add_argument("--android-compiler", type=Path, help="Android NDK 目标架构 C++ 编译器")
    parser.add_argument("--serial", help="执行对照程序的 Android 设备序列号")
    # 原始源码命令行参数。
    args = parser.parse_args()
    if bool(args.android_compiler) != bool(args.serial):
        parser.error("设备对照需要同时提供 --android-compiler 和 --serial")
    # 当前变更所在的项目根目录。
    # 通过项目配置识别仓库根，兼容归档前后的目录层级。
    repo = next(parent for parent in Path(__file__).resolve().parents
                if (parent / "openspec/config.yaml").is_file() and (parent / "android/settings.gradle.kts").is_file())
    # 本项目迁移后的代理源码。
    migrated = repo / "android/performance-anr/src/main/cpp/MatrixTracer.cc"
    # 两个代理分别直接从源码提取，只替换底层调用桩。
    cpp = CPP_HARNESS.replace("/* ORIGINAL */", wrapper("Original", args.matrix_source))
    cpp = cpp.replace("/* MIGRATED */", wrapper("Migrated", migrated))
    with tempfile.TemporaryDirectory(prefix="matrix-hook-") as directory:
        # 自动清理的对照源码和可执行程序。
        source_file = Path(directory) / "hook-diff.cc"
        executable = Path(directory) / "hook-diff"
        source_file.write_text(cpp, encoding="utf-8")
        if args.android_compiler:
            subprocess.run([str(args.android_compiler), "-std=c++17", "-static-libstdc++", str(source_file), "-o", str(executable)], check=True)
            # 唯一临时路径仅属于本次测试，并在结束时清理。
            remote = "/data/local/tmp/matrix-hook-" + uuid.uuid4().hex
            adb = ["adb", "-s", args.serial]
            try:
                subprocess.run([*adb, "push", str(executable), remote], check=True, stdout=subprocess.DEVNULL)
                subprocess.run([*adb, "shell", "chmod", "700", remote], check=True)
                subprocess.run([*adb, "shell", remote], check=True)
            finally:
                subprocess.run([*adb, "shell", "rm", "-f", remote], check=False)
        else:
            subprocess.run(["clang++", "-std=c++17", str(source_file), "-o", str(executable)], check=True)
            subprocess.run([str(executable)], check=True)


if __name__ == "__main__":
    main()
