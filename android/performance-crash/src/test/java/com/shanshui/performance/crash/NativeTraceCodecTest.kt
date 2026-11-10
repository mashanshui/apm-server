package com.shanshui.performance.crash

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** 验证有限回溯记录的身份、位宽、模块关联和中断边界。 */
class NativeTraceCodecTest {
    /** 不依赖 Android 设备的合成崩溃核心。 */
    private val core = NativeCoreRecord(1794200000123, 123, 456, 11, 1, "0x0", "arm64-v8a", mapOf("pc" to "0xffffffffffffffff"))

    /** 原始相对地址和同名线程分别保留，不读取或合成内存内容。 */
    @Test
    fun decodesBoundedThreadsAndExactModuleOffsets() {
        val result = requireNotNull(NativeTraceCodec.decode(record(), core))
        assertEquals("worker", result.crashedThreadName)
        assertEquals("abcdef12", result.modules.single().elfBuildId)
        assertEquals("0xffffffffffffffff", result.backtrace.frames.single().relativePc)
        assertEquals("0x0", result.backtrace.frames.single().functionOffset)
        assertEquals(123, result.nativeThreads.threads.single().systemTid)
        assertEquals("worker", result.nativeThreads.threads.single().name)
        assertTrue(result.nativeThreads.threads.single().mainThread == true)
        assertEquals("count_limit", result.nativeThreads.threads.single().backtrace.reason)
    }

    /** 半写、错误 PID、悬空模块引用和超出 32 位地址不能被恢复。 */
    @Test
    fun rejectsCorruptionWrongIdentityAndDanglingModules() {
        val bytes = record()
        assertNull(NativeTraceCodec.decode(bytes.copyOf(bytes.size - 1), core))
        assertNull(NativeTraceCodec.decode(bytes.copyOf().apply { this[25] = 0 }, core))
        assertNull(NativeTraceCodec.decode(record(pid = 124), core))
        assertNull(NativeTraceCodec.decode(record(moduleIndex = 2), core))
        assertNull(NativeTraceCodec.decode(bytes, core.copy(abi = "x86")))
    }

    /** 解码器不会让潜在敏感文本绕过输出端的首次持久化约束。 */
    @Test
    fun rejectsSensitiveNamesInMalformedRecords() {
        assertNull(NativeTraceCodec.decode(record(name = "138-0013-8000"), core))
        assertNull(NativeTraceCodec.decode(record(name = "abc@example.test"), core))
        assertNull(NativeTraceCodec.decode(record(name = "apm_ak_AbCdefGHijk"), core))
    }

    /** 第二版记录携带安全 abort 消息，旧记录仍可恢复且拒绝敏感文本。 */
    @Test
    fun readsSafeAbortMessageAndRejectsSensitiveOrWrongSignal() {
        /** 与测试回溯绑定的合成 SIGABRT 核心。 */
        val abortCore = core.copy(signal = 6, faultAddress = null)
        assertEquals("synthetic assertion failed", NativeTraceCodec.decode(record(version = 2,
            abortMessage = "synthetic assertion failed"), abortCore)?.abortMessage)
        assertNull(NativeTraceCodec.decode(record(version = 2, abortMessage = "X-App-Key: apm_ak_AbCdefGHijk"), abortCore))
        assertNull(NativeTraceCodec.decode(record(version = 2, abortMessage = "synthetic assertion failed"), core))
        assertNull(NativeTraceCodec.decode(record(), core)?.abortMessage)
    }

    /** 按 dumper 的小端协议构造固定合成数据并写入校验。 */
    private fun record(pid: Int = 123, moduleIndex: Int = 0, name: String = "worker", version: Int = 1,
                       abortMessage: String = ""): ByteArray {
        val output = ByteBuffer.allocate(2048).order(ByteOrder.LITTLE_ENDIAN)
        output.put(byteArrayOf(65, 78, 84, 82)).putInt(version).putInt(0).putLong(core.occurredAt)
        output.putInt(pid).putInt(core.systemTid).putInt(1).putInt(0).putInt(2).putInt(1)
        putText(output, "libexample.so")
        putText(output, "abcdef12")
        output.putInt(core.systemTid).putInt(0).putInt(0).putInt(0)
        putText(output, name)
        output.putInt(1).putInt(moduleIndex).putLong(-1L).putLong(0)
        putText(output, "sample")
        output.putInt(pid).putInt(1).putInt(1).putInt(1)
        putText(output, "worker")
        output.putInt(1).putInt(0).putLong(0x11).putLong(0)
        putText(output, "")
        if (version >= 2) putText(output, abortMessage)
        val length = output.position() + 4
        output.putInt(8, length)
        var checksum = -2128831035
        for (index in 0 until output.position()) checksum = (checksum xor (output.array()[index].toInt() and 0xff)) * 16777619
        output.putInt(checksum)
        return output.array().copyOf(length)
    }

    /** 写入有限 UTF-8 文本供测试解析。 */
    private fun putText(output: ByteBuffer, text: String) {
        val bytes = text.toByteArray(Charsets.US_ASCII)
        output.putInt(bytes.size).put(bytes)
    }
}
