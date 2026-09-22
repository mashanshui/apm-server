package com.shanshui.apmserver.symbol.api;

/** 上传校验或 Retrace 达到共享并发上限。 */
public class SymbolParserBusyException extends RuntimeException {

    /** 创建可重试的繁忙错误。 */
    public SymbolParserBusyException() {
        super("符号表解析资源繁忙，请稍后重试");
    }
}
