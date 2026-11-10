package com.shanshui.performance.crash

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** 验证 Native 核心在部分写入和跨位宽条件下的恢复边界。 */
class NativeCoreCodecTest {
    /** 有符号 Long 位模式必须保留为完整无符号地址，零故障地址也不能丢失。 */
    @Test
    fun decodesPreciseAddressesAndOriginalIdentity() {
        val decoded = requireNotNull(NativeCoreCodec.decode(record(abi = 1, fault = 0, registers = listOf("pc" to -1L))))
        assertEquals(1794200000123, decoded.occurredAt)
        assertEquals(123, decoded.pid)
        assertEquals(456, decoded.systemTid)
        assertEquals(11, decoded.signal)
        assertEquals("arm64-v8a", decoded.abi)
        assertEquals("0x0", decoded.faultAddress)
        assertEquals("0xffffffffffffffff", decoded.registers["pc"])
    }

    /** 记录头、长度、校验和中任意一处损坏都不能变成可上传事件。 */
    @Test
    fun rejectsInterruptedAndCorruptedRecords() {
        val valid = record(abi = 1, fault = 0, registers = listOf("pc" to 42L))
        assertNull(NativeCoreCodec.decode(valid.copyOf(valid.size - 1)))
        val corrupted = valid.copyOf().apply { this[20] = (this[20].toInt() xor 1).toByte() }
        assertNull(NativeCoreCodec.decode(corrupted))
        val wrongVersion = valid.copyOf().apply { this[4] = 2 }
        assertNull(NativeCoreCodec.decode(wrongVersion))
    }

    /** 32 位 ABI 不能接受超出其位宽的故障地址或寄存器。 */
    @Test
    fun rejectsWrongWidthAndDuplicateRegisters() {
        assertNull(NativeCoreCodec.decode(record(abi = 2, fault = 0x100000000L, registers = emptyList())))
        assertNull(NativeCoreCodec.decode(record(abi = 3, fault = 0, registers = listOf("pc" to 0x100000000L))))
        assertNull(NativeCoreCodec.decode(record(abi = 1, fault = 0, registers = listOf("pc" to 1L, "pc" to 2L))))
    }

    /** 构造不含真实设备数据的跨 ABI 二进制记录。 */
    private fun record(abi: Int, fault: Long, registers: List<Pair<String, Long>>): ByteArray {
        val buffer = ByteBuffer.allocate(60 + registers.size * 16).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put(byteArrayOf('A'.code.toByte(), 'N'.code.toByte(), 'C'.code.toByte(), 'R'.code.toByte()))
        buffer.putInt(1).putInt(buffer.capacity()).putLong(1794200000123)
        buffer.putInt(123).putInt(456).putInt(11).putInt(1).putInt(1).putLong(fault)
        buffer.putInt(registers.size).putInt(abi)
        for ((name, value) in registers) {
            val field = ByteArray(8)
            name.toByteArray(Charsets.US_ASCII).copyInto(field)
            buffer.put(field).putLong(value)
        }
        var checksum = -2128831035
        for (index in 0 until buffer.position()) checksum = (checksum xor (buffer.array()[index].toInt() and 0xff)) * 16777619
        buffer.putInt(checksum)
        return buffer.array()
    }
}
