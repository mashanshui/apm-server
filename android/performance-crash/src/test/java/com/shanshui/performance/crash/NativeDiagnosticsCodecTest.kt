package com.shanshui.performance.crash

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** 验证原生日志、进程内存与 FD 的独立提交协议。 */
class NativeDiagnosticsCodecTest {
    /** 合成的原核心身份。 */
    private val core = NativeCoreRecord(1794200000123, 123, 456, 11, 1, "0x0", "arm64-v8a", mapOf("pc" to "0x1"))

    /** 保留合法零值、权限状态和安全 FD 类别。 */
    @Test
    fun decodesIndependentPartitionsAndZeroValues() {
        val result = requireNotNull(NativeDiagnosticsCodec.decode(record(), core))
        assertEquals("safe line", result.logcat.main!!.lines.single())
        assertEquals("permission_denied", result.logcat.system!!.reason)
        assertEquals(0L, result.memory.rssBytes)
        assertEquals(4096L, result.memory.vssBytes)
        assertNull(result.memory.swapBytes)
        assertEquals("[path]", result.openFiles.entries.single().target)
        assertEquals(2, result.openFiles.count)
        assertTrue(result.openFiles.truncated)
    }

    /** 核心身份、尾部校验和受限 proc 字段不匹配时不导入诊断。 */
    @Test
    fun rejectsCorruptionAndUnrelatedProcText() {
        val bytes = record()
        assertNull(NativeDiagnosticsCodec.decode(bytes.copyOf(bytes.size - 1), core))
        assertNull(NativeDiagnosticsCodec.decode(bytes.copyOf().apply { this[30] = (this[30].toInt() xor 1).toByte() }, core))
        assertNull(NativeDiagnosticsCodec.decode(bytes, core.copy(pid = 124)))
        assertNull(NativeDiagnosticsCodec.decode(record(statusText = "memory map: secret\n"), core))
        assertNull(NativeDiagnosticsCodec.decode(record(line = "memory near x0"), core))
        assertNull(NativeDiagnosticsCodec.decode(record(line = "Demo: 0000007fe0ef1ffa  0000007be0ef226f"), core))
    }

    /** 不可信输入的普通文本还会经过 Java 侧第二次脱敏。 */
    @Test
    fun sanitizesOptionalLogTextAgain() {
        val result = requireNotNull(NativeDiagnosticsCodec.decode(record(line = "token=synthetic-secret"), core))
        assertTrue(result.logcat.main!!.lines.single().contains("<redacted>"))
    }

    /** 生成有限诊断记录和完整性校验。 */
    private fun record(line: String = "safe line", statusText: String = "VmRSS: 0 kB\nVmSize: 4 kB\n"): ByteArray {
        val buffer = ByteBuffer.allocate(4096).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put(byteArrayOf(65, 78, 68, 71)).putInt(1).putInt(0).putLong(core.occurredAt).putInt(core.pid)
        partition(buffer, 0, 0, 0)
        buffer.putInt(1)
        text(buffer, line)
        partition(buffer, 2, 2, 0)
        buffer.putInt(0)
        partition(buffer, 0, 0, 0)
        buffer.putInt(0)
        partition(buffer, 1, 4, 0)
        buffer.putLong(0).putLong(4096).putLong(-1).putLong(8192)
        text(buffer, "MemTotal: 4 kB\n")
        text(buffer, statusText)
        text(buffer, "Max open files 1024 2048\n")
        partition(buffer, 1, 6, 1)
        buffer.putInt(2).putInt(1).putInt(4).putInt(5)
        val length = buffer.position() + 4
        buffer.putInt(8, length)
        var checksum = -2128831035
        for (index in 0 until buffer.position()) checksum = (checksum xor (buffer.array()[index].toInt() and 0xff)) * 16777619
        buffer.putInt(checksum)
        return buffer.array().copyOf(length)
    }

    /** 写入采集状态与固定原时间。 */
    private fun partition(buffer: ByteBuffer, status: Int, reason: Int, truncated: Int) {
        buffer.putInt(status).putInt(reason).putInt(truncated).putLong(core.occurredAt)
    }

    /** 写入 UTF-8 字符串与长度。 */
    private fun text(buffer: ByteBuffer, value: String) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        buffer.putInt(bytes.size).put(bytes)
    }
}
