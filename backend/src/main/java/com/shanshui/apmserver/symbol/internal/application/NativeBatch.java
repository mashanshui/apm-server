package com.shanshui.apmserver.symbol.internal.application;

import com.shanshui.apmserver.symbol.api.SymbolFileMetadata;
import com.shanshui.apmserver.symbol.internal.elf.ElfMetadata;
import com.shanshui.apmserver.symbol.internal.storage.SymbolFileStore;
import java.util.List;
import java.util.UUID;

/** SO 批次的有限协议与事务材料，不保存原 ZIP。 */
public final class NativeBatch {
    /** 工具类型不创建实例。 */
    private NativeBatch() {}
    /** 客户端确认的完整身份集合；symbolId/revision 同为空表示预期不存在。 */
    public record Expected(/** 实际 ABI。 */ String abi, /** 完整 ELF 身份。 */ String elfBuildId,
                           /** 预期当前记录。 */ UUID symbolId, /** 预期文件修订。 */ Integer revision) {}
    /** 经过校验的单份不可变文件及实际元数据。 */
    public record Candidate(/** 受限文件名。 */ String name, /** 实际 ELF 内容。 */ ElfMetadata elf,
                            /** 摘要与大小。 */ SymbolFileStore.StagedFile staged, /** 已发布受控键。 */ String key) {}
    /** 原子提交的批次结果。 */
    public record Result(/** 当前元数据。 */ List<SymbolFileMetadata> items, /** 新建数。 */ int createdCount,
                         /** 替换数。 */ int replacedCount, /** 相同内容数。 */ int unchangedCount) {}
}
