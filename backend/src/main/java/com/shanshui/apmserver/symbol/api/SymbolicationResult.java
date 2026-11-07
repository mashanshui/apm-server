package com.shanshui.apmserver.symbol.api;

/** 一次受限 R8 计算结果，持久化状态由 Crash 后台流程发布。 */
public record SymbolicationResult(
        String text,
        Status status,
        String reason) {

    /** 完整还原文本的规范化输入；失败结果不能参与正式指纹。 */
    public RetraceFingerprintInput fingerprintInput() {
        if (status != Status.SYMBOLICATED || text == null) {
            throw new IllegalStateException("还原未成功，不能生成正式指纹输入");
        }
        return RetraceFingerprintInput.fromText(text);
    }

    /** 本次还原状态。 */
    public enum Status {
        SYMBOLICATED,
        FAILED
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
