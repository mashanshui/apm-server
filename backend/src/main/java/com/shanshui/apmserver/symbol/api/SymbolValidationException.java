package com.shanshui.apmserver.symbol.api;

/** 符号表请求或 mapping 内容校验失败。 */
public class SymbolValidationException extends RuntimeException {

    /** 错误码。 */
    private final String code;
    /** HTTP 状态码。 */
    private final int status;

    /** 创建带错误码和 HTTP 状态的校验异常。 */
    public SymbolValidationException(String code, String message, int status) {
        super(message);
        this.code = code;
        this.status = status;
    }

    /** 返回安全错误码。 */
    public String getCode() {
        return code;
    }

    /** 返回 HTTP 状态码。 */
    public int getStatus() {
        return status;
    }
}
