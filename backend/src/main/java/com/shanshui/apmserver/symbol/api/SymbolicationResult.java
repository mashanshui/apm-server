package com.shanshui.apmserver.symbol.api;

/** 一次请求内的 Retrace 结果，不代表任何持久化事件状态。 */
public record SymbolicationResult(
        String text,
        Status status,
        String reason) {

    /** 本次还原状态。 */
    public enum Status {
        SYMBOLICATED,
        RAW_ONLY,
        FAILED
    }

    /** 返回缺少符号表的降级结果。 */
    public static SymbolicationResult missing() {
        return new SymbolicationResult(null, Status.RAW_ONLY, "mapping_missing");
    }

    /** 返回资源繁忙的降级结果。 */
    public static SymbolicationResult busy() {
        return new SymbolicationResult(null, Status.FAILED, "parser_busy");
    }

    /** 返回处理失败的降级结果。 */
    public static SymbolicationResult failed(String reason) {
        return new SymbolicationResult(null, Status.FAILED, reason);
    }
}
