package com.shanshui.apmserver.crash.internal.domain;

import com.shanshui.apmserver.telemetry.api.EventValidationException;
import com.shanshui.apmserver.telemetry.api.ValidationIssue;

import java.util.List;

/** 对部分写入和重复请求验证已固定的采集内容。 */
public final class CrashCaptureIdentity {
    /** 此类只提供不可变身份规则。 */
    private CrashCaptureIdentity() { }

    /** 新摘要必须一致；历史无摘要事件不能通过重复上报追加诊断。 */
    public static void requireCompatible(String stored, String incoming, boolean hasDiagnostics) {
        String previous = stored == null ? "" : stored;
        String requested = incoming == null ? "" : incoming;
        if ((!previous.isEmpty() && !previous.equals(requested)) || (previous.isEmpty() && hasDiagnostics)) {
            throw new EventValidationException(List.of(new ValidationIssue("CRASH_CONTENT_CONFLICT", "同一事件的采集内容不一致")));
        }
    }

    /** 历史事实或孤立状态缺少摘要时，恢复另一侧也保持历史空摘要。 */
    public static CrashState withoutDigest(CrashState state) {
        return new CrashState(state.appId(), state.eventId(), state.buildId(), state.eventTime(), state.stateVersion(),
                state.status(), state.reason(), state.stackFormat(), state.stackPayload(), state.exceptionType(),
                state.fingerprint(), state.fingerprintVersion(), state.groupingKind(), state.symbolId(), state.symbolRevision(),
                state.symbolSha256(), state.processedAt(), state.retryAt(), "", state.threadStacksPayload());
    }
}
