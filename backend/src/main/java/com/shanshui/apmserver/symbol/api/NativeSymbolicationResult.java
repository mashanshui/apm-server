package com.shanshui.apmserver.symbol.api;

import java.util.List;
import java.util.Map;

/** 固定 SO 来源的一次有限地址批次结果。 */
public record NativeSymbolicationResult(
        /** 规范地址到单物理帧结果的映射。 */ Map<String, Frame> frames,
        /** 批次级安全失败原因，成功时为空。 */ String reason) {
    /** 单个物理帧的有序内联结果及安全原因。 */
    public record Frame(
            /** 按工具顺序排列的内联项。 */ List<Inline> inline,
            /** 未取得真实函数时的安全原因。 */ String reason) { }

    /** 不包含编译机绝对路径的函数及源码位置。 */
    public record Inline(
            /** 函数名，未知时为空。 */ String function,
            /** 不含目录的源码文件名。 */ String file,
            /** 正数行号，未知时为空。 */ Integer line,
            /** 正数列号，未知时为空。 */ Integer column) { }
}
