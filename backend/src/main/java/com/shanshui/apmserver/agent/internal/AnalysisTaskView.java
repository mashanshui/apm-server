package com.shanshui.apmserver.agent.internal;

import java.time.Instant;
import java.util.UUID;

/** 网页任务投影，不返回 Worker 或模型密钥。 */
public record AnalysisTaskView(
        /** 任务标识。 */ UUID taskId,
        /** 应用归属。 */ UUID appId,
        /** 只解释此事件。 */ String eventId,
        /** 原 Issue 归组指纹。 */ String fingerprint,
        /** 创建者用于取消权限判断。 */ UUID createdBy,
        /** 数据库创建时间。 */ Instant createdAt,
        /** 执行状态。 */ String state,
        /** 缺失前提或准备失败原因。 */ String blockReason,
        /** 用于拒绝历史模式执行的证据版本，可空。 */ Integer evidenceSchemaVersion,
        /** 固定证据标识。 */ UUID evidenceId,
        /** 固定证据摘要。 */ String evidenceSha256,
        /** 终态内容已过期，摘要仍保留。 */ boolean contentExpired) {
}
