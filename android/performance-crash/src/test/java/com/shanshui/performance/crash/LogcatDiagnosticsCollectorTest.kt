package com.shanshui.performance.crash

import com.google.gson.GsonBuilder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 通过可注入进程验证分块读取、PID 过滤和超时清理。 */
class LogcatDiagnosticsCollectorTest {
    /** 旧系统使用精确 PID 列过滤；中文跨读取块后仍完整还原。 */
    @Test
    fun filtersPidAndKeepsUtf8AcrossChunks() {
        val commands = mutableListOf<List<String>>()
        val lines = "11-08 10:00:00.000 123 456 I Tag: 中文日志\n" +
            "11-08 10:00:00.001 999 456 I Tag: 123 不能误收\n"
        val collector = LogcatDiagnosticsCollector(starter = { command ->
            commands += command
            FakeProcess(if (command[2] == "main") TinyInput(lines.toByteArray(StandardCharsets.UTF_8)) else ByteArrayInputStream(byteArrayOf()))
        })
        try {
            val result = collector.collect(123, 23, System.nanoTime() + TimeUnit.SECONDS.toNanos(2), false)
            assertEquals("ok", result.status)
            assertEquals(1, result.main!!.lines.size)
            assertTrue(result.main!!.lines.single().contains("中文日志"))
            assertFalse(result.main!!.lines.single().contains("不能误收"))
            assertTrue(commands.none { "--pid" in it })
            assertEquals("200", commands[0][7])
            assertEquals("50", commands[1][7])
            assertEquals("50", commands[2][7])
            assertTrue(result.system!!.lines.isEmpty())
        } finally {
            collector.close()
        }
    }

    /** 长且没有换行的输出只保留受限前缀，并标明截断。 */
    @Test
    fun limitsAnUnterminatedLongLine() {
        val gson = GsonBuilder().disableHtmlEscaping().create()
        val line = "11-08 10:00:00.000 123 456 I Tag: " + "中\"".repeat(4000)
        val collector = LogcatDiagnosticsCollector(starter = { command ->
            FakeProcess(ByteArrayInputStream(if (command[2] == "main") line.toByteArray(StandardCharsets.UTF_8) else byteArrayOf()))
        })
        try {
            val result = collector.collect(123, 35, System.nanoTime() + TimeUnit.SECONDS.toNanos(2), false)
            val main = result.main!!
            assertEquals("partial", main.status)
            assertTrue(main.truncated)
            assertTrue(gson.toJson(main.lines.single()).toByteArray(StandardCharsets.UTF_8).size <= 2048)
        } finally {
            collector.close()
        }
    }

    /** 无权限与成功读取空日志具有不同状态，API 24 以上固定传入 PID 过滤参数。 */
    @Test
    fun distinguishesPermissionFailureFromEmptySuccess() {
        val commands = mutableListOf<List<String>>()
        val collector = LogcatDiagnosticsCollector(starter = { command ->
            commands += command
            val bytes = if (command[2] == "system") "permission denied\n".toByteArray() else byteArrayOf()
            FakeProcess(ByteArrayInputStream(bytes), if (command[2] == "system") 1 else 0)
        })
        try {
            val result = collector.collect(123, 24, System.nanoTime() + TimeUnit.SECONDS.toNanos(2), false)
            assertEquals("ok", result.main!!.status)
            assertTrue(result.main!!.lines.isEmpty())
            assertEquals("unavailable", result.system!!.status)
            assertEquals("permission_denied", result.system!!.reason)
            assertTrue(commands.all { it.windowed(2).contains(listOf("--pid", "123")) })
        } finally {
            collector.close()
        }
    }

    /** 真正的子进程写满错误管道时，合并读取仍可继续读取标准输出并结束。 */
    @Test
    fun drainsStderrLargerThanPipeCapacity() {
        val collector = LogcatDiagnosticsCollector(starter = { command ->
            if (command[2] == "main") startLogcatProcess(listOf("/bin/sh", "-c",
                "dd if=/dev/zero bs=1024 count=128 2>/dev/null | tr '\\000' x >&2; " +
                    "printf '\\n11-08 10:00:00.000 123 456 I Tag: after-error\\n'"))
            else FakeProcess(ByteArrayInputStream(byteArrayOf()))
        })
        try {
            val result = collector.collect(123, 35, System.nanoTime() + TimeUnit.SECONDS.toNanos(2), false)
            assertEquals("ok", result.main!!.status)
            assertTrue(result.main!!.lines.single().contains("after-error"))
        } finally {
            collector.close()
        }
    }

    /** 读取阻塞时 500ms 超时销毁子进程并关闭流，后续缓冲区不再启动。 */
    @Test
    fun destroysProcessAndClosesBlockedStreamAtDeadline() {
        val input = BlockingInput()
        val process = FakeProcess(input)
        var starts = 0
        val collector = LogcatDiagnosticsCollector(starter = { starts++; process })
        try {
            val start = System.nanoTime()
            val result = collector.collect(123, 35, start + TimeUnit.SECONDS.toNanos(2), false)
            assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) < 1500)
            assertEquals("timeout", result.main!!.reason)
            assertTrue(process.destroyed)
            assertTrue(input.closed)
            assertEquals(1, starts)
        } finally {
            collector.close()
        }
    }

    /** 模拟每次最多读取两个字节的流，覆盖 UTF-8 跨块拼接。 */
    private class TinyInput(data: ByteArray) : InputStream() {
        /** 实际字节来源。 */ private val input = ByteArrayInputStream(data)
        /** 读取一个字节。 */ override fun read(): Int = input.read()
        /** 每次最多读取两个字节。 */
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int = input.read(buffer, offset, minOf(length, 2))
    }

    /** 可由关闭动作解除阻塞的测试流。 */
    private class BlockingInput : InputStream() {
        /** 等待关闭的门闩。 */ private val release = CountDownLatch(1)
        /** 是否已经关闭。 */ @Volatile var closed = false
        /** 在流关闭前模拟阻塞读取。 */
        override fun read(): Int { release.await(); return -1 }
        /** 按同一门闩模拟块读取。 */
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int = read()
        /** 关闭时解除所有阻塞读取。 */
        override fun close() { closed = true; release.countDown() }
    }

    /** 提供可控输出和退出码的子进程替身。 */
    private class FakeProcess(
        /** 已合并的输出流。 */ private val input: InputStream,
        /** 正常退出码。 */ private val code: Int = 0,
    ) : Process() {
        /** 关闭输出所需的空流。 */ private val output = ByteArrayOutputStream()
        /** 测试中未使用的独立错误流。 */ private val error = ByteArrayInputStream(byteArrayOf())
        /** 是否已收到销毁请求。 */ @Volatile var destroyed = false
        /** 返回模拟标准输入。 */ override fun getOutputStream(): OutputStream = output
        /** 返回合并输出。 */ override fun getInputStream(): InputStream = input
        /** 返回空错误流。 */ override fun getErrorStream(): InputStream = error
        /** 返回指定退出码。 */ override fun waitFor(): Int = code
        /** 返回指定退出码。 */ override fun exitValue(): Int = code
        /** 销毁时关闭输出以解除读取阻塞。 */
        override fun destroy() { destroyed = true; input.close() }
    }
}
