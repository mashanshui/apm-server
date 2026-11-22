#!/usr/bin/env python3
"""使用指定 Matrix 源码和同一组 trace，对照迁移前后的死锁解析结果。"""

import argparse
from pathlib import Path
import subprocess
import tempfile


def detector_class(path: Path) -> str:
    """提取原始 Java 内部类，不改写其解析实现。"""
    # 同级的结束花括号为四格缩进，内部类方法和嵌套类使用更深缩进。
    source = path.read_text(encoding="utf-8")
    # 原始内部类的起始位置。
    start = source.index("    private static class SimpleDeadLockDetector {")
    # 原始内部类的结束位置。
    end = source.index("\n    }\n", start) + len("\n    }")
    return source[start:end]


# 只替换 Android 日志与注解依赖；两份解析器分别直接取自各自的源码。
JAVA_HARNESS = r'''
import java.lang.reflect.*;
import java.util.*;
import java.util.regex.*;

@interface NonNull {}
class MatrixLog {
    /** 避免主机测试依赖 Android 日志。 */
    static void e(String tag, String message) {}
}
class Original {
    /** 原实现调用日志时使用的占位标签。 */
    private static final String TAG = "original";
/* ORIGINAL_DETECTOR */
}
class Migrated {
    /** 迁移实现调用日志时使用的占位标签。 */
    private static final String TAG = "migrated";
/* MIGRATED_DETECTOR */
}
public class DetectorDiff {
    /** 按实际回调分支记录结果，比较线程文本、等待 ID 和锁类型。 */
    private static String evaluate(Class<?> outer, String[] lines) throws Exception {
        /** 获取原本私有的解析器，不改变生产源码可见性。 */
        Class<?> type = Class.forName(outer.getName() + "$SimpleDeadLockDetector");
        /** 构造解析器实例。 */
        Constructor<?> constructor = type.getDeclaredConstructor();
        constructor.setAccessible(true);
        /** 当前版本的解析器实例。 */
        Object detector = constructor.newInstance();
        /** 将同一份样本逐行输入原实现和迁移实现。 */
        Method parse = type.getDeclaredMethod("parseLine", String.class);
        parse.setAccessible(true);
        for (String line : lines) parse.invoke(detector, line);
        /** 完成 EOF 处理并取得各项原始结果。 */
        boolean deadlock = (Boolean) call(type, detector, "hasDeadLock");
        /** 原实现保存的主线程文本。 */
        String main = (String) call(type, detector, "getMainThreadInfo");
        /** 锁环中第一个等待线程文本。 */
        String first = (String) call(type, detector, "getLockHeldThread1Info");
        /** 锁环中最后一个等待线程文本。 */
        String last = (String) call(type, detector, "getLockHeldThread2Info");
        /** 原实现返回的等待线程及锁类型。 */
        Map.Entry<?, ?> waiting = (Map.Entry<?, ?>) call(type, detector, "getWaitingThreadsInfo");
        /** 按两份 onANRDumpTrace 的相同条件推导回调类别。 */
        String callback = deadlock ? "DEADLOCK" :
                main.contains("android.os.MessageQueue.nativePollOnce") ? "NATIVE_POLL_ONCE" : "NONE";
        return Arrays.deepToString(new Object[] {callback, main, first, last, waiting.getKey(), waiting.getValue()});
    }

    /** 调用私有解析器上的无参读取方法。 */
    private static Object call(Class<?> type, Object detector, String name) throws Exception {
        /** 已移除访问限制的目标方法。 */
        Method method = type.getDeclaredMethod(name);
        method.setAccessible(true);
        return method.invoke(detector);
    }

    /** 在两份源码上运行相同样本，并验证预期的 Matrix 回调分支。 */
    public static void main(String[] args) throws Exception {
        /** 锁环、无等待、EOF、格式变化和非法线程 ID 样本。 */
        String[][] samples = {
            {"\"main\" prio=5 tid=1 Blocked",
             "  - waiting to lock <0x1> (a java.lang.Object) held by thread 2", "",
             "\"worker\" prio=5 tid=2 Blocked",
             "  - waiting to lock <0x2> (a java.lang.String) held by thread 1"},
            {"\"main\" prio=5 tid=1 Native",
             "  at android.os.MessageQueue.nativePollOnce(Native method)", ""},
            {"\"main\" prio=5 tid=1 Blocked",
             "  at android.os.MessageQueue.nativePollOnce(Native method)",
             "  - waiting to lock <0x1> (a java.lang.Object) held by thread 2"},
            {"\"main\" prio=5 tid=1 Blocked (timeout)",
             "  - waiting to lock <0x1> (a java.lang.Object) held by thread 1", ""},
            {"\"main\" prio=5 tid=1 Blocked",
             "  - waiting to lock <0x1> (an Object) held by thread 1", ""},
            {"\"main\" prio=5 tid=1 Blocked",
             "  - waiting to lock <0x1> (a java.lang.Object) held by thread 1", ""},
            {"\"main\" prio=5 tid=999999999999999999999 Blocked",
             "  - waiting to lock <0x1> (a java.lang.Object) held by thread 1", ""},
        };
        /** 每个样本由原实现决定的回调类别。 */
        String[] expected = {"DEADLOCK", "NONE", "NATIVE_POLL_ONCE", "NONE", "NONE", "DEADLOCK", "NONE"};
        for (int i = 0; i < samples.length; i++) {
            /** 同一个样本的原版与迁移版结果。 */
            String original = evaluate(Original.class, samples[i]);
            /** 同一个样本的迁移版结果。 */
            String migrated = evaluate(Migrated.class, samples[i]);
            if (!original.equals(migrated) || !original.startsWith("[" + expected[i] + ",")) {
                throw new AssertionError("样本 " + (i + 1) + " 的原版与迁移版结果不一致");
            }
            System.out.println("样本 " + (i + 1) + "：" + expected[i] + "，原版和迁移版一致");
        }
    }
}
'''


def main() -> None:
    """在临时目录编译并执行原版和迁移版对照，不修改任一源码。"""
    # 原版路径由调用者显式指定，避免将本机检出路径写入仓库。
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("matrix_source", type=Path, help="Matrix 的 SignalAnrTracer.java 路径")
    # 用户指定的 Matrix 来源路径。
    args = parser.parse_args()
    # 当前变更位于仓库 openspec/changes 之下。
    # 通过项目配置识别仓库根，兼容归档前后的目录层级。
    repo = next(parent for parent in Path(__file__).resolve().parents
                if (parent / "openspec/config.yaml").is_file() and (parent / "android/settings.gradle.kts").is_file())
    # 当前迁移实现的来源路径。
    migrated_source = repo / "android/performance-anr/src/main/java/com/shanshui/performance/anr/internal/SignalAnrTracer.java"
    # 仅在临时验证代码中合并两份原始内部类。
    java = JAVA_HARNESS.replace("/* ORIGINAL_DETECTOR */", detector_class(args.matrix_source))
    java = java.replace("/* MIGRATED_DETECTOR */", detector_class(migrated_source))
    with tempfile.TemporaryDirectory(prefix="matrix-detector-") as directory:
        # 编译结束后自动删除的验证源码。
        java_file = Path(directory) / "DetectorDiff.java"
        java_file.write_text(java, encoding="utf-8")
        subprocess.run(["javac", "-encoding", "UTF-8", str(java_file)], check=True)
        subprocess.run(["java", "-cp", directory, "DetectorDiff"], check=True)


if __name__ == "__main__":
    main()
