package com.shanshui.apmserver.crash.internal.domain;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

/** 一次发布的完整状态；堆栈、归组与来源不能分别更新。 */
public record CrashState(
        /** 所属应用。 */ UUID appId,
        /** 原事件身份。 */ String eventId,
        /** 不变构建。 */ String buildId,
        /** 原发生时间。 */ Instant eventTime,
        /** 独立单调版本，0 为初始接收。 */ long stateVersion,
        /** 当前三态。 */ String status,
        /** 安全原因类别。 */ String reason,
        /** raw_chain、raw_native 或 retrace_text。 */ String stackFormat,
        /** 原始链 JSON 或完整还原文本，只有一份。 */ String stackPayload,
        /** 当前异常类型。 */ String exceptionType,
        /** 当前归组指纹。 */ String fingerprint,
        /** 指纹算法版本。 */ String fingerprintVersion,
        /** temporary 或 resolved。 */ String groupingKind,
        /** 实际尝试的符号表身份。 */ UUID symbolId,
        /** 实际尝试的文件版本。 */ Integer symbolRevision,
        /** 实际尝试的摘要。 */ String symbolSha256,
        /** 实际处理时间。 */ Instant processedAt,
        /** 临时失败下次重试；确定性失败为空。 */ Instant retryAt,
        /** 首次采集内容摘要；历史事件为空。 */ String captureSha256,
        /** 与主异常同版本的其他线程当前栈 JSON。 */ String threadStacksPayload) {

    /** 构造未携带诊断的历史或测试状态。 */
    public CrashState(UUID appId, String eventId, String buildId, Instant eventTime, long stateVersion,
                      String status, String reason, String stackFormat, String stackPayload, String exceptionType,
                      String fingerprint, String fingerprintVersion, String groupingKind, UUID symbolId,
                      Integer symbolRevision, String symbolSha256, Instant processedAt, Instant retryAt) {
        this(appId, eventId, buildId, eventTime, stateVersion, status, reason, stackFormat, stackPayload, exceptionType,
                fingerprint, fingerprintVersion, groupingKind, symbolId, symbolRevision, symbolSha256, processedAt,
                retryAt, "", "");
    }

    /** 当前存储拒绝未知状态，防止旧格式被默认解释为 pending。 */
    public CrashState {
        captureSha256 = captureSha256 == null ? "" : captureSha256;
        threadStacksPayload = threadStacksPayload == null ? "" : threadStacksPayload;
        if (status == null || !Set.of("pending", "failed", "symbolicated").contains(status)
                || appId == null || eventId == null || buildId == null || eventTime == null
                || stateVersion < 0 || stackPayload == null || stackPayload.isBlank()
                || fingerprint == null || fingerprintVersion == null
                || (!captureSha256.isEmpty() && !captureSha256.matches("[a-f0-9]{64}"))) {
            throw new IllegalArgumentException("Crash 当前状态不合法");
        }
        if ("symbolicated".equals(status)) {
            if (!"retrace_text".equals(stackFormat) || !"resolved".equals(groupingKind)
                    || symbolId == null || symbolRevision == null || symbolRevision < 1
                    || symbolSha256 == null || !symbolSha256.matches("[a-f0-9]{64}") || processedAt == null) {
                throw new IllegalArgumentException("Crash 成功状态缺少完整结果或来源");
            }
        } else if ("raw_native".equals(stackFormat)) {
            // Native 待支持不表示进入 JVM 后台，来源和重试时间均不可伪造。
            if (!"pending".equals(status) || !"native_symbolication_deferred".equals(reason)
                    || !"temporary".equals(groupingKind) || symbolId != null || symbolRevision != null
                    || symbolSha256 != null || processedAt != null || retryAt != null) {
                throw new IllegalArgumentException("Native 原始状态或来源不合法");
            }
        } else if (!"raw_chain".equals(stackFormat) || !"temporary".equals(groupingKind)) {
            throw new IllegalArgumentException("Crash 未成功状态必须保存合法原始材料");
        }
    }
}
