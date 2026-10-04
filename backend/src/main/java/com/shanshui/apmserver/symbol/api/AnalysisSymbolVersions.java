package com.shanshui.apmserver.symbol.api;

import java.util.UUID;

/** 分析发布阶段的短事务版本核对；不执行 Retrace 或读取文件。 */
public interface AnalysisSymbolVersions {
    /** 调用方事务结束前锁定当前行，避免核对后被替换再发布旧 READY。 */
    boolean lockAndMatch(UUID appId, String buildId, UUID symbolId, int revision, String sha256);
}
