package com.shanshui.performance.crash

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** 已完整提交的 Native 核心记录；所有身份来自崩溃进程。 */
internal data class NativeCoreRecord(
    /** 原发生时间，Unix 毫秒。 */ val occurredAt: Long,
    /** 原 Android 进程号。 */ val pid: Int,
    /** 原崩溃线程的系统 TID。 */ val systemTid: Int,
    /** 信号编号。 */ val signal: Int,
    /** 平台信号码。 */ val code: Int,
    /** 可取得的故障地址；零地址不是缺失。 */ val faultAddress: String?,
    /** 崩溃时进程实际 ABI。 */ val abi: String,
    /** 架构寄存器名称和无损十六进制值。 */ val registers: Map<String, String>,
)

/** 严格解析有长度与校验值的有限核心，不把半写记录当作有效事件。 */
internal object NativeCoreCodec {
    /** 当前 Native 核心记录的版本。 */
    private const val VERSION = 1
    /** 固定头与尾部校验字节。 */
    private const val FIXED_BYTES = 60
    /** 每个寄存器的名称和数值字节。 */
    private const val REGISTER_BYTES = 16
    /** 记录最多允许的寄存器项数。 */
    private const val MAX_REGISTERS = 64
    /** FNV-1a 32 位初值。 */
    private const val FNV_OFFSET = -2128831035
    /** FNV-1a 32 位乘数。 */
    private const val FNV_PRIME = 16777619

    /** 返回完整合法记录；格式、长度、校验或身份不合法时返回 null。 */
    fun decode(bytes: ByteArray): NativeCoreRecord? {
        if (bytes.size < FIXED_BYTES || bytes.size > FIXED_BYTES + MAX_REGISTERS * REGISTER_BYTES) return null
        if (bytes[0] != 'A'.code.toByte() || bytes[1] != 'N'.code.toByte() ||
            bytes[2] != 'C'.code.toByte() || bytes[3] != 'R'.code.toByte()
        ) return null
        val input = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        input.position(4)
        if (input.int != VERSION || input.int != bytes.size) return null
        val occurredAt = input.long
        val pid = input.int
        val tid = input.int
        val signal = input.int
        val code = input.int
        val hasFault = input.int
        val fault = input.long
        val count = input.int
        val abi = when (input.int) {
            1 -> "arm64-v8a"
            2 -> "armeabi-v7a"
            3 -> "x86"
            4 -> "x86_64"
            else -> return null
        }
        if (occurredAt <= 0 || pid <= 0 || tid <= 0 || signal !in listOf(4, 6, 7, 8, 11) ||
            hasFault !in 0..1 || count !in 0..MAX_REGISTERS || bytes.size != FIXED_BYTES + count * REGISTER_BYTES
        ) return null
        val is32Bit = abi == "armeabi-v7a" || abi == "x86"
        if (is32Bit && fault ushr 32 != 0L) return null
        val registers = linkedMapOf<String, String>()
        repeat(count) {
            val nameBytes = ByteArray(8)
            input.get(nameBytes)
            val zero = nameBytes.indexOf(0)
            val length = if (zero < 0) nameBytes.size else zero
            if (nameBytes.drop(length).any { it != 0.toByte() }) return null
            val name = nameBytes.copyOf(length).toString(Charsets.US_ASCII)
            if (!name.matches(Regex("[a-z][a-z0-9_]{0,7}")) || name in registers) return null
            val value = input.long
            if (is32Bit && value ushr 32 != 0L) return null
            registers[name] = hex(value)
        }
        if (input.int != checksum(bytes, bytes.size - 4)) return null
        return NativeCoreRecord(occurredAt, pid, tid, signal, code, if (hasFault == 1) hex(fault) else null, abi, registers)
    }

    /** 将无符号位模式编码为服务端接受的规范十六进制字符串。 */
    private fun hex(value: Long): String = "0x" + java.lang.Long.toHexString(value)

    /** 根据原始字节计算 FNV-1a，不改变寄存器或地址。 */
    private fun checksum(bytes: ByteArray, length: Int): Int {
        var value = FNV_OFFSET
        for (index in 0 until length) value = (value xor (bytes[index].toInt() and 0xff)) * FNV_PRIME
        return value
    }
}
