package com.shanshui.apmserver.symbol.api;

/** 符号表注册信息或文件卷暂时不可用。 */
public class SymbolStoreUnavailableException extends RuntimeException {

    /** 创建一个不泄漏内部路径的存储故障。 */
    public SymbolStoreUnavailableException(Throwable cause) {
        super("符号表存储暂时不可用", cause);
    }

    /** 创建一个带安全提示的存储故障。 */
    public SymbolStoreUnavailableException(String message) {
        super(message);
    }
}
