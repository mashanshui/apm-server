package com.shanshui.apmserver.symbol.api;

/** 同一应用构建上传不同 mapping 时的冲突。 */
public class SymbolConflictException extends RuntimeException {

    /** 现有符号表元数据。 */
    private final SymbolFileMetadata current;

    /** 创建带现有版本信息的冲突异常。 */
    public SymbolConflictException(SymbolFileMetadata current) {
        super("该构建已经存在不同内容的 mapping，请确认替换");
        this.current = current;
    }

    /** 返回当前版本，供网页展示摘要和 revision。 */
    public SymbolFileMetadata getCurrent() {
        return current;
    }
}
