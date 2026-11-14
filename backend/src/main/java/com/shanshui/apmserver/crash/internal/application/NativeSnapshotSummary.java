package com.shanshui.apmserver.crash.internal.application;

import com.shanshui.apmserver.crash.api.NativeCurrentAuxiliaryStacks;
import com.shanshui.apmserver.crash.api.NativeStack;
import com.shanshui.apmserver.crash.internal.domain.CrashState;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/** 将完整主辅栈汇总为一个不可拆分的 Native 状态候选。 */
@Component
public class NativeSnapshotSummary {
    /** 确定性编码主辅状态。 */
    private final ObjectMapper mapper;

    /** 使用应用 JSON 映射器。 */
    public NativeSnapshotSummary(ObjectMapper mapper) { this.mapper = mapper; }

    /** 摘要及物理主帧状态计数。 */
    public record Result(
            /** 仍绑定旧基础版本的完整候选。 */ CrashState state,
            /** 主栈成功物理帧数。 */ long succeeded,
            /** 主栈待处理物理帧数。 */ long pending,
            /** 主栈确定性失败物理帧数。 */ long failed,
            /** 主栈不可处理物理帧数。 */ long notApplicable) { }

    /** 基于已处理的完整主辅栈汇总，不修改原捕获身份或归组。 */
    public Result summarize(CrashState base, NativeStack main, NativeCurrentAuxiliaryStacks auxiliary) {
        if (base == null || main == null || auxiliary == null) throw new IllegalArgumentException("Native 汇总缺少状态");
        var mainFrames = main.backtrace().frames();
        long succeeded = mainFrames.stream().filter(frame -> "symbolicated".equals(frame.resolution().status())).count();
        long pending = mainFrames.stream().filter(frame -> "pending".equals(frame.resolution().status())).count();
        long failed = mainFrames.stream().filter(frame -> "failed".equals(frame.resolution().status())).count();
        long unavailable = mainFrames.stream().filter(frame -> "not_applicable".equals(frame.resolution().status())).count();
        boolean waiting = mainFrames.stream().anyMatch(frame -> "pending".equals(frame.resolution().status()) || frame.resolution().retryAt() != null);
        String status = mainFrames.isEmpty() || waiting ? "pending" : succeeded == mainFrames.size() ? "symbolicated" : "failed";
        String reason = mainFrames.isEmpty() ? "capture_stack_unavailable" : "symbolicated".equals(status) ? null
                : mainFrames.stream().map(NativeStack.Frame::resolution).filter(resolution ->
                        "pending".equals(status) ? "pending".equals(resolution.status()) || resolution.retryAt() != null
                                : !"symbolicated".equals(resolution.status()))
                        .map(NativeStack.Resolution::reason).filter(java.util.Objects::nonNull).findFirst().orElse("native_unavailable");
        var allFrames = new ArrayList<>(mainFrames);
        auxiliary.nativeThreads().forEach(thread -> allFrames.addAll(thread.backtrace().frames()));
        var javaStacks = new ArrayList<NativeStack.JavaStack>();
        if (main.javaStack() != null) javaStacks.add(main.javaStack());
        auxiliary.javaThreads().forEach(thread -> javaStacks.add(thread.stack()));
        boolean work = allFrames.stream().anyMatch(frame -> !List.of("symbolicated", "not_applicable").contains(frame.resolution().status()))
                || javaStacks.stream().anyMatch(stack -> !List.of("symbolicated", "not_applicable").contains(stack.symbolicationStatus()));
        var processed = new ArrayList<Instant>();
        var retries = new ArrayList<Instant>();
        allFrames.forEach(frame -> { if (frame.resolution().processedAt() != null) processed.add(frame.resolution().processedAt());
            if (frame.resolution().retryAt() != null) retries.add(frame.resolution().retryAt()); });
        javaStacks.forEach(stack -> { if (stack.processedAt() != null) processed.add(stack.processedAt());
            if (stack.retryAt() != null) retries.add(stack.retryAt()); });
        Instant processedAt = processed.stream().max(Instant::compareTo).orElse(base.processedAt());
        Instant retryAt = retries.stream().min(Instant::compareTo).orElse(null);
        String auxiliaryPayload = auxiliary.javaThreads().isEmpty() && auxiliary.nativeThreads().isEmpty()
                && base.threadStacksPayload().isEmpty() ? "" : mapper.writeValueAsString(auxiliary);
        var candidate = new CrashState(base.appId(), base.eventId(), base.buildId(), base.eventTime(), base.stateVersion(), status, reason,
                "native_stack", mapper.writeValueAsString(main), base.exceptionType(), base.fingerprint(), base.fingerprintVersion(),
                base.groupingKind(), null, null, null, processedAt, retryAt, base.captureSha256(), auxiliaryPayload, work);
        return new Result(candidate, succeeded, pending, failed, unavailable);
    }
}
