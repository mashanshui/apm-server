package com.shanshui.apmserver.symbol.api;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.time.Duration;

/** 符号表注册、固定版本读取和请求内 R8 Retrace 的公共边界。 */
public interface SymbolRegistry {

    /** 仅检查版本是否有活动 mapping，不读取文件或遥测。 */
    boolean hasVersion(UUID appId, String appVersion);

    /** 根据应用和构建标识取得一个固定版本的读取租约。 */
    Optional<SymbolFileLease> acquire(UUID appId, String buildId);

    /** 按应用版本与可选构建选择专用或默认 mapping 并固定文件租约。 */
    Optional<SymbolFileLease> acquireVersioned(UUID appId, String appVersion, String buildId);

    /** 使用租约中的固定 mapping 还原一组完整堆栈文本。 */
    SymbolicationResult retrace(SymbolFileLease lease, List<String> stackLines);

    /** 使用同一文件版本有界还原辅助线程；忙或超时立即返回安全失败。 */
    SymbolicationResult retraceAuxiliary(SymbolFileLease lease, List<String> stackLines, long maxOutputBytes, Duration wait);
}
