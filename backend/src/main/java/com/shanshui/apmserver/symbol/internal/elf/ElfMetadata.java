package com.shanshui.apmserver.symbol.internal.elf;

/** 从 ELF 实际文件读取的模块身份及可用符号信息。 */
public record ElfMetadata(
        /** ELF 文件头对应的 Android ABI。 */ String abi,
        /** 完整 GNU Build ID 的小写十六进制。 */ String elfBuildId,
        /** 完整符号表是否包含已定义函数。 */ boolean functionSymbols,
        /** 是否存在非空调试信息段。 */ boolean debugInfo,
        /** 是否存在非空调试行信息段。 */ boolean debugLine) {
}
