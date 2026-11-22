#!/usr/bin/env python3
"""用可控 Android 桩对照 Matrix 的消息阈值、错误状态和 debugger 去重。"""

import argparse
from pathlib import Path
import re
import subprocess
import tempfile


def method(source: str, signature: str) -> str:
    """提取同级缩进的原始 Java 方法。"""
    # 保留完整方法体，只去掉方法前的 Android 注解。
    start = source.index("    " + signature)
    # 方法结束花括号缩进四格，内部块具有更深缩进。
    end = source.index("\n    }\n", start) + len("\n    }")
    return source[start:end]


def constants(source: str) -> str:
    """从各自源码提取原阈值、次数、间隔及线程名。"""
    # 需要与方法一起对照的常量名称。
    names = (
        "CHECK_ANR_STATE_THREAD_NAME", "CHECK_ERROR_STATE_INTERVAL", "ANR_DUMP_MAX_TIME",
        "CHECK_ERROR_STATE_COUNT", "FOREGROUND_MSG_THRESHOLD", "BACKGROUND_MSG_THRESHOLD",
    )
    # 保留原声明及表达式，避免在测试桩中重新指定生产阈值。
    declarations = []
    for name in names:
        match = re.search(r"private static final\s+(?:String|int|long)\s+" + name + r"\s*=\s*[^;]+;", source)
        if match is None:
            raise ValueError("未找到 Matrix 常量：" + name)
        declarations.append(match.group())
    return "\n".join(declarations)


def compare_method_bodies(original: Path, migrated: Path, original_signature: str, migrated_signature: str) -> None:
    """忽略注释、空白和安全日志差异，比较实际采样方法主体。"""
    # 两份源码均来自调用者指定的 Matrix 检出和本仓库。
    original_body = method(original.read_text(encoding="utf-8"), original_signature).split("{", 1)[1]
    migrated_body = method(migrated.read_text(encoding="utf-8"), migrated_signature).split("{", 1)[1]

    def normalize(body: str) -> str:
        """移除方法注释与不含报告正文的日志适配差异。"""
        # 这些方法中的原版日志不参与字段和控制流判断。
        body = re.sub(r"/\*.*?\*/|//[^\n]*", "", body, flags=re.DOTALL)
        body = re.sub(r"MatrixLog\.[ie]\(.*?;", "", body, flags=re.DOTALL)
        return "".join(body.split())

    if normalize(original_body) != normalize(migrated_body):
        raise ValueError("采样方法与 Matrix 不一致：" + original_signature)


# 测试桩只控制输入、线程调度和时间，不替换待对照的方法。
JAVA_HARNESS = r'''
import java.lang.reflect.Field;
import java.util.*;

/** 用于比较的安全日志桩。 */
class MatrixLog {
    /** 不输出测试输入。 */
    static void i(String tag, String message, Object... args) {}
    /** 不输出测试输入。 */
    static void e(String tag, String message, Object... args) {}
}
/** 用于构造系统服务名称。 */
class Context { static final String ACTIVITY_SERVICE = "activity"; }
/** 可控消息。 */
class Message {
    /** 消息执行时间。 */
    final long when;
    /** 保存测试时间。 */
    Message(long value) { when = value; }
    /** 返回执行时间。 */
    long getWhen() { return when; }
    /** 返回可识别的消息文本。 */
    public String toString() { return "synthetic"; }
}
/** 保留与 Android 消息队列相同的反射字段。 */
class MessageQueue { private Message mMessages; }
/** 模拟系统字段发生变化。 */
class ChangedMessageQueue extends MessageQueue {}
/** 返回可控消息队列。 */
class Looper {
    /** 当前测试队列。 */
    static MessageQueue queue;
    /** 返回主循环桩。 */
    static Looper getMainLooper() { return new Looper(); }
    /** 返回当前队列。 */
    MessageQueue getQueue() { return queue; }
}
/** 固定运行时间，精确比较边界。 */
class SystemClock {
    /** 测试使用的运行时间。 */
    static final long NOW = 100_000L;
    /** 返回固定运行时间。 */
    static long uptimeMillis() { return NOW; }
}
/** 模拟系统进程错误状态查询。 */
class ActivityManager {
    /** NOT_RESPONDING 标记和必要字段。 */
    static class ProcessErrorStateInfo {
        static final int NOT_RESPONDING = 2;
        int pid;
        int uid;
        int condition;
        String processName = "synthetic";
        String longMsg = "synthetic";
        /** 创建指定进程状态。 */
        ProcessErrorStateInfo(int uid, int pid, int condition) {
            this.uid = uid; this.pid = pid; this.condition = condition;
        }
    }
    /** 本次查询返回的状态列表。 */
    static List<ProcessErrorStateInfo> states;
    /** 模拟系统服务抛异常。 */
    static boolean fail;
    /** 已执行查询次数。 */
    static int calls;
    /** 返回可控系统状态。 */
    List<ProcessErrorStateInfo> getProcessesInErrorState() {
        calls++;
        if (fail) throw new IllegalStateException("synthetic");
        return states;
    }
}
/** 返回可控 ActivityManager。 */
class Application {
    /** 返回测试系统服务。 */
    Object getSystemService(String name) { return new ActivityManager(); }
}
/** 提供原 Matrix 的 Application 回退依赖。 */
class Matrix {
    /** 返回 Matrix 桩。 */
    static Matrix with() { return new Matrix(); }
    /** 返回测试应用。 */
    Application getApplication() { return new Application(); }
}
/** 记录 debugger 分支请求的主线程 Java 栈。 */
class Utils {
    /** 已读取主线程栈的次数。 */
    static int calls;
    /** 返回测试 Java 栈。 */
    static String getMainThreadJavaStackTrace() { calls++; return "MAIN_JAVA"; }
}
/** 同步执行后台任务，并记录原实现要求的休眠次数与总时长。 */
class Thread {
    /** 待执行的测试任务。 */
    private final Runnable runnable;
    /** 休眠次数。 */
    static int sleeps;
    /** 累计休眠毫秒数。 */
    static long totalMillis;
    /** 保存原实现创建的任务。 */
    Thread(Runnable task, String name) { runnable = task; }
    /** 同步执行，以确定性比较逻辑顺序。 */
    void start() { runnable.run(); }
    /** 记录休眠参数，不让主机测试实际等待。 */
    static void sleep(long millis) { sleeps++; totalMillis += millis; }
}
/* ORIGINAL */
/* MIGRATED */

/** 比较两份实际源码在固定输入下的结果。 */
public class JudgmentDiff {
    /** 对照边界值、进程状态和 20 秒去重。 */
    public static void main(String[] args) throws Exception {
        /** 每项必须同时符合原版与迁移版结果。 */
        String[] expected = {
            "true|synthetic|-2001|0|0|0|0||[]",
            "false|synthetic|-2000|0|0|0|0||[]",
            "true|synthetic|-10001|0|0|0|0||[]",
            "false|synthetic|-10000|0|0|0|0||[]",
            "false|synthetic|777|0|0|0|0||[]",
            "false|unset|777|0|0|0|0||[]",
            "false|unset|777|0|0|0|0||[]",
            "true|unset|777|1|0|0|0||[]",
            "false|unset|777|1|0|0|0||[]",
            "true|unset|777|1|0|0|0||[]",
            "false|unset|777|1|0|0|0||[]",
            "false|unset|777|1|0|0|0||[]",
            "false|synthetic|-2001|0|0|0|0||[REPORT:false:true]",
            "false|unset|777|1|0|0|0||[REPORT:true:true]",
            "false|unset|777|40|40|20000|0||[]",
            "false|unset|777|0|0|0|0||[]",
            "false|synthetic|-2001|0|0|0|1|MAIN_JAVA|[REPORT:false:false]",
            "false|unset|777|40|40|20000|1|MAIN_JAVA|[]",
        };
        for (int index = 0; index < expected.length; index++) {
            /** 原版与迁移版在同一场景中的全部结果。 */
            String original = Original.run(index);
            String migrated = Migrated.run(index);
            if (!original.equals(migrated) || !original.equals(expected[index])) {
                throw new AssertionError("样本 " + (index + 1) + " 不一致：" + original + " / " + migrated);
            }
            System.out.println("样本 " + (index + 1) + "：原版与迁移版一致");
        }
    }
}
'''


# 各版本共用的桩状态和固定样本；实际判断方法由对应源码填充。
WRAPPER = r'''
class /* NAME */ {
    /** Matrix 原日志标签。 */
    private static final String TAG = "test";
    /* CONSTANTS */
    /** 当前前后台状态。 */
    private static boolean currentForeground;
    /** Matrix 记录的消息时间差。 */
    private static long anrMessageWhen;
    /** Matrix 记录的消息文本。 */
    private static String anrMessageString;
    /** 最近一次报告时间。 */
    private static long lastReportedTimeStamp;
    /** Matrix debugger 分支采到的 Java 栈。 */
    private static String nativeBacktraceStackTrace;
    /** 原实现日志中引用的 Java 栈。 */
    private static String stackTrace;
    /** 当前测试应用。 */
    private static Application sApplication;
    /** 记录实际通知来源。 */
    private static final List<String> events = new ArrayList<>();

    /** 记录回调并保留 Matrix 的报告时间语义。 */
    private static void report(boolean fromError, boolean isSigQuit) {
        events.add("REPORT:" + fromError + ":" + isSigQuit);
        lastReportedTimeStamp = System.currentTimeMillis();
    }
    /* BLOCKED */
    /* ERROR */
    /* CYCLE */
    /* CONFIRM */
    /* DEBUGGER */

    /** 设置消息队列的待执行消息。 */
    private static void message(long when) throws Exception {
        Looper.queue = new MessageQueue();
        Field field = MessageQueue.class.getDeclaredField("mMessages");
        field.setAccessible(true);
        field.set(Looper.queue, new Message(when));
    }
    /** 执行指定样本并返回判断、静态字段和通知记录。 */
    static String run(int scenario) throws Exception {
        currentForeground = true;
        anrMessageWhen = 777;
        anrMessageString = "unset";
        lastReportedTimeStamp = 0;
        nativeBacktraceStackTrace = "";
        stackTrace = "";
        sApplication = new Application();
        events.clear();
        Looper.queue = new MessageQueue();
        ActivityManager.states = null;
        ActivityManager.fail = false;
        ActivityManager.calls = 0;
        Thread.sleeps = 0;
        Thread.totalMillis = 0;
        Utils.calls = 0;
        /** 待检查的同步判断结果。 */
        boolean value = false;
        /** 测试进程匹配的系统 ANR 状态。 */
        ActivityManager.ProcessErrorStateInfo own = new ActivityManager.ProcessErrorStateInfo(42, 100, 2);
        switch (scenario) {
            case 0: message(SystemClock.NOW - 2001); value = isMainThreadBlocked(); break;
            case 1: message(SystemClock.NOW - 2000); value = isMainThreadBlocked(); break;
            case 2: currentForeground = false; message(SystemClock.NOW - 10001); value = isMainThreadBlocked(); break;
            case 3: currentForeground = false; message(SystemClock.NOW - 10000); value = isMainThreadBlocked(); break;
            case 4: message(0); value = isMainThreadBlocked(); break;
            case 5: value = isMainThreadBlocked(); break;
            case 6: Looper.queue = new ChangedMessageQueue(); value = isMainThreadBlocked(); break;
            case 7: ActivityManager.states = Arrays.asList(own); value = checkErrorState(); break;
            case 8: ActivityManager.states = Arrays.asList(new ActivityManager.ProcessErrorStateInfo(43, 101, 2), own); value = checkErrorState(); break;
            case 9: ActivityManager.states = Arrays.asList(new ActivityManager.ProcessErrorStateInfo(42, 101, 2), own); value = checkErrorState(); break;
            case 10: value = checkErrorState(); break;
            case 11: ActivityManager.fail = true; value = checkErrorState(); break;
            case 12: message(SystemClock.NOW - 2001); confirmRealAnr(true); break;
            case 13: ActivityManager.states = Arrays.asList(own); confirmRealAnr(true); break;
            case 14: confirmRealAnr(true); break;
            case 15: lastReportedTimeStamp = System.currentTimeMillis() - 19_000; onNativeBacktraceDumped(); break;
            case 16: lastReportedTimeStamp = System.currentTimeMillis() - 21_000; message(SystemClock.NOW - 2001); onNativeBacktraceDumped(); break;
            case 17: lastReportedTimeStamp = System.currentTimeMillis() - 21_000; onNativeBacktraceDumped(); break;
            default: throw new IllegalArgumentException("未知样本");
        }
        return value + "|" + anrMessageString + "|" + anrMessageWhen + "|" + ActivityManager.calls + "|" +
               Thread.sleeps + "|" + Thread.totalMillis + "|" + Utils.calls + "|" + nativeBacktraceStackTrace + "|" + events;
    }
}
'''


def wrapper(name: str, path: Path) -> str:
    """为指定来源生成保留原方法的独立测试包装。"""
    # 同一检出的完整 Matrix Java 源码。
    source = path.read_text(encoding="utf-8")
    # 逐项提取原方法，确保被比较的是实际源码而不是重写算法。
    signatures = {
        "BLOCKED": "private static boolean isMainThreadBlocked() {",
        "ERROR": "private static boolean checkErrorState() {",
        "CYCLE": "private static void checkErrorStateCycle(boolean isSigQuit) {",
        "CONFIRM": "private static void confirmRealAnr(final boolean isSigQuit) {",
        "DEBUGGER": "private static void onNativeBacktraceDumped() {",
    }
    # 在临时类中使用对应版本的常量与方法。
    result = WRAPPER.replace("/* NAME */", name).replace("/* CONSTANTS */", constants(source))
    for key, signature in signatures.items():
        result = result.replace("/* " + key + " */", method(source, signature))
    return result


def main() -> None:
    """在临时目录编译并运行判断对照，不修改任一生产源码。"""
    # 显式指定原版 Matrix 文件，避免依赖本机固定路径。
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("matrix_source", type=Path, help="Matrix 的 SignalAnrTracer.java 路径")
    # 原始来源命令行参数。
    args = parser.parse_args()
    # 当前变更所在的仓库根。
    # 通过项目配置识别仓库根，兼容归档前后的目录层级。
    repo = next(parent for parent in Path(__file__).resolve().parents
                if (parent / "openspec/config.yaml").is_file() and (parent / "android/settings.gradle.kts").is_file())
    # 当前迁移后的 Java 源码。
    migrated = repo / "android/performance-anr/src/main/java/com/shanshui/performance/anr/internal/SignalAnrTracer.java"
    # 原版和迁移版各自的辅助方法目录。
    original_util = args.matrix_source.parent.parent / "util"
    migrated_util = migrated.parent
    # 采样控制流和辅助字段读取必须保持原版语义。
    compare_method_bodies(args.matrix_source, migrated, "private synchronized static void onANRDumped() {", "private synchronized static void onANRDumped() {")
    compare_method_bodies(args.matrix_source, migrated, "public static String readCgroup() {", "public static String readCgroup() {")
    compare_method_bodies(original_util / "Utils.java", migrated_util / "Utils.java", "public static String getMainThreadJavaStackTrace() {", "static String getMainThreadJavaStackTrace() {")
    compare_method_bodies(original_util / "AppForegroundUtil.java", migrated_util / "AppForegroundUtil.java", "private static boolean isActivityInterestingToUser() {", "private static boolean isActivityInterestingToUser() {")
    print("Java 采样、cgroup、主线程栈和前后台辅助方法主体一致", flush=True)
    # 合并临时测试桩及两份原始方法。
    java = JAVA_HARNESS.replace("/* ORIGINAL */", wrapper("Original", args.matrix_source))
    java = java.replace("/* MIGRATED */", wrapper("Migrated", migrated))
    with tempfile.TemporaryDirectory(prefix="matrix-judgment-") as directory:
        # 提供原方法引用的 Android 进程 ID 桩。
        process_file = Path(directory) / "android/os/Process.java"
        process_file.parent.mkdir(parents=True)
        process_file.write_text("package android.os; public class Process { public static int myUid() { return 42; } public static int myPid() { return 100; } }", encoding="utf-8")
        # 可自动清理的主机对照程序。
        java_file = Path(directory) / "JudgmentDiff.java"
        java_file.write_text(java, encoding="utf-8")
        subprocess.run(["javac", "-encoding", "UTF-8", str(java_file), str(process_file)], check=True)
        subprocess.run(["java", "-cp", directory, "JudgmentDiff"], check=True)


if __name__ == "__main__":
    main()
