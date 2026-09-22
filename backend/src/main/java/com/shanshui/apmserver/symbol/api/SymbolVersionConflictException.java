package com.shanshui.apmserver.symbol.api;

/** 替换操作基于过期 revision 时的并发冲突。 */
public class SymbolVersionConflictException extends RuntimeException {

    /** 当前符号表元数据。 */
    private final SymbolFileMetadata current;

    /** 创建带当前版本的并发冲突。 */
    public SymbolVersionConflictException(SymbolFileMetadata current) {
        super("符号表版本已变化，请刷新后重新确认");
        this.current = current;
    }

    /** 返回当前版本。 */
    public SymbolFileMetadata getCurrent() {
        return current;
    }
}
