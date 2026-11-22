package com.shanshui.performance.anr

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 使用固定 trace 样本验证 Matrix 原有锁解析结果。 */
class MatrixDeadLockDetectorTest {
    /** 两个线程形成环时保留等待线程及主线程信息。 */
    @Test
    fun detectsCycleAndFinalLineWithoutBlankTerminator() {
        /** 最后一个线程故意不以空行结束，验证 Matrix 的 EOF 分支。 */
        val detector = detector(
            "\"main\" prio=5 tid=1 Blocked",
            "  - waiting to lock <0x1> (a java.lang.Object) held by thread 2",
            "",
            "\"worker\" prio=5 tid=2 Blocked",
            "  - waiting to lock <0x2> (a java.lang.String) held by thread 1",
        )
        assertTrue(detector.hasDeadLock())
        assertTrue(detector.mainThreadInfo().contains("tid=1"))
        assertArrayEquals(intArrayOf(1, 2), detector.waitingThreads().sortedArray())
    }

    /** Matrix 仅在主线程存在锁等待时保存其信息，不扩大 nativePollOnce 的识别范围。 */
    @Test
    fun doesNotSaveMainThreadWithoutLockWait() {
        /** 主线程虽处于 nativePollOnce，但没有 Matrix 能识别的等待关系。 */
        val detector = detector(
            "\"main\" prio=5 tid=1 Native",
            "  at android.os.MessageQueue.nativePollOnce(Native method)",
            "",
        )
        assertFalse(detector.hasDeadLock())
        assertEquals("", detector.mainThreadInfo())
    }

    /** 对主线程有可识别等待但不成环的情况保留原始线程文本。 */
    @Test
    fun keepsWaitingMainThreadWithoutInventingCycle() {
        /** 等待目标不存在时不能构造锁环。 */
        val detector = detector(
            "\"main\" prio=5 tid=1 Blocked",
            "  at android.os.MessageQueue.nativePollOnce(Native method)",
            "  - waiting to lock <0x1> (a java.lang.Object) held by thread 2",
        )
        assertFalse(detector.hasDeadLock())
        assertTrue(detector.mainThreadInfo().contains("android.os.MessageQueue.nativePollOnce"))
    }

    /** 构造 Matrix 私有解析器并依次输入 trace 行。 */
    private fun detector(vararg lines: String): Detector {
        /** Matrix 内部解析器类型，生产代码不为测试更改可见性。 */
        val type = Class.forName("com.shanshui.performance.anr.internal.SignalAnrTracer\$SimpleDeadLockDetector")
        /** 原始解析器实例。 */
        val instance = type.getDeclaredConstructor().apply { isAccessible = true }.newInstance()
        /** 输入一行的原始方法。 */
        val parseLine = type.getDeclaredMethod("parseLine", String::class.java).apply { isAccessible = true }
        lines.forEach { parseLine.invoke(instance, it) }
        return Detector(type, instance)
    }

    /** 测试专用反射包装，保持被测源码结构不变。 */
    private class Detector(
        /** Matrix 原始类型。 */
        private val type: Class<*>,
        /** Matrix 原始对象。 */
        private val instance: Any,
    ) {
        /** 执行 Matrix 原有死锁判断。 */
        fun hasDeadLock(): Boolean = call("hasDeadLock") as Boolean

        /** 读取 Matrix 记录的主线程文本。 */
        fun mainThreadInfo(): String = call("getMainThreadInfo") as String

        /** 读取 Matrix 记录的锁等待线程。 */
        fun waitingThreads(): IntArray = (call("getWaitingThreadsInfo") as Map.Entry<*, *>).key as IntArray

        /** 调用原始零参数方法。 */
        private fun call(name: String): Any? =
            type.getDeclaredMethod(name).apply { isAccessible = true }.invoke(instance)
    }
}
