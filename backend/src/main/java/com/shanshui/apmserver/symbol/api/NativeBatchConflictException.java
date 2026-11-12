package com.shanshui.apmserver.symbol.api;

import java.util.List;

/** 全批 SO 冲突快照，重试必须重新确认全部身份。 */
public final class NativeBatchConflictException extends RuntimeException {
    /** 有界候选集合。 */
    private final List<Entry> entries;
    /** 保存无副作用的当前批次快照。 */
    public NativeBatchConflictException(List<Entry> entries) { super("SO 批次需要重新确认"); this.entries = List.copyOf(entries); }
    /** 返回全部已校验模块，包括预期新建项。 */
    public List<Entry> entries() { return entries; }
    /** 当前缺失记录用 null 表示，不隐式授权覆盖后来新增的模块。 */
    public record Entry(/** ABI。 */ String abi, /** 完整 ELF 身份。 */ String elfBuildId,
                        /** 传入摘要。 */ String incomingSha256, /** 当前登记。 */ SymbolFileMetadata current) {}
}
