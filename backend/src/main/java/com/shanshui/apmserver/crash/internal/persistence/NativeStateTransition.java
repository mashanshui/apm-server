package com.shanshui.apmserver.crash.internal.persistence;

import com.shanshui.apmserver.crash.api.NativeAuxiliaryStacks;
import com.shanshui.apmserver.crash.api.NativeCrash;
import com.shanshui.apmserver.crash.api.NativeCurrentAuxiliaryStacks;
import com.shanshui.apmserver.crash.api.NativeStack;
import com.shanshui.apmserver.crash.internal.domain.CrashState;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Objects;

/** 检查 Native 增量不能改变原物理身份或任何已成功单元。 */
@Component
public class NativeStateTransition {
    /** 用于严格读取已确定的状态材料。 */
    private final ObjectMapper mapper;

    /** 注入与发布链路一致的 JSON 映射器。 */
    public NativeStateTransition(ObjectMapper mapper) { this.mapper = mapper; }

    /** 验证候选在同一基础版本上单调补齐，不重算或替换既有成功。 */
    public void validate(CrashState base, CrashState candidate) {
        if (base == null || candidate == null || !"native_stack".equals(candidate.stackFormat())
                || !("raw_native".equals(base.stackFormat()) || "native_stack".equals(base.stackFormat()))
                || !Objects.equals(base.appId(), candidate.appId()) || !Objects.equals(base.eventId(), candidate.eventId())
                || !Objects.equals(base.buildId(), candidate.buildId()) || !Objects.equals(base.eventTime(), candidate.eventTime())
                || base.stateVersion() != candidate.stateVersion() || !Objects.equals(base.captureSha256(), candidate.captureSha256())
                || !Objects.equals(base.exceptionType(), candidate.exceptionType()) || !Objects.equals(base.fingerprint(), candidate.fingerprint())
                || !Objects.equals(base.fingerprintVersion(), candidate.fingerprintVersion()) || !Objects.equals(base.groupingKind(), candidate.groupingKind())) {
            throw new IllegalArgumentException("Native 增量改变了事件身份或基础版本");
        }
        NativeStack next = read(candidate.stackPayload(), NativeStack.class);
        if ("raw_native".equals(base.stackFormat())) {
            NativeCrash raw = read(base.stackPayload(), NativeCrash.class);
            require(Objects.equals(raw.abi(), next.abi()) && Objects.equals(raw.systemTid(), next.systemTid())
                    && Objects.equals(raw.threadName(), next.threadName()) && Objects.equals(raw.mainThread(), next.mainThread())
                    && Objects.equals(raw.signal(), next.signal()) && Objects.equals(raw.registers(), next.registers())
                    && Objects.equals(raw.modules(), next.modules()), "Native 原始现场不能改变");
            rawBacktrace(raw.backtrace(), next.backtrace());
            rawJava(raw.javaStack(), next.javaStack());
            rawAuxiliary(base.threadStacksPayload(), candidate.threadStacksPayload());
        } else {
            NativeStack previous = read(base.stackPayload(), NativeStack.class);
            require(Objects.equals(previous.abi(), next.abi()) && Objects.equals(previous.systemTid(), next.systemTid())
                    && Objects.equals(previous.threadName(), next.threadName()) && Objects.equals(previous.mainThread(), next.mainThread())
                    && Objects.equals(previous.signal(), next.signal()) && Objects.equals(previous.registers(), next.registers())
                    && Objects.equals(previous.modules(), next.modules()), "Native 原始现场不能改变");
            backtrace(previous.backtrace(), next.backtrace());
            javaStack(previous.javaStack(), next.javaStack());
            auxiliary(base.threadStacksPayload(), candidate.threadStacksPayload());
        }
    }

    /** 初次转换必须保留原采集回溯及每个物理帧身份。 */
    private void rawBacktrace(NativeCrash.Backtrace raw, NativeStack.Backtrace next) {
        require(raw != null && next != null && Objects.equals(raw.status(), next.status())
                && Objects.equals(raw.reason(), next.reason()) && Objects.equals(raw.truncated(), next.truncated())
                && Objects.equals(raw.capturedAt(), next.capturedAt()) && raw.frames().size() == next.frames().size(), "Native 原始回溯不能改变");
        for (int index = 0; index < raw.frames().size(); index++) {
            var left = raw.frames().get(index);
            var right = next.frames().get(index);
            require(Objects.equals(left.index(), right.index()) && Objects.equals(left.moduleId(), right.moduleId())
                    && Objects.equals(left.relativePc(), right.relativePc()) && Objects.equals(left.functionName(), right.functionName())
                    && Objects.equals(left.functionOffset(), right.functionOffset()), "Native 原物理帧不能改变");
        }
    }

    /** 后续轮次保留采集信息、物理帧及所有首次成功结果。 */
    private void backtrace(NativeStack.Backtrace previous, NativeStack.Backtrace next) {
        require(previous != null && next != null && Objects.equals(previous.status(), next.status())
                && Objects.equals(previous.reason(), next.reason()) && Objects.equals(previous.truncated(), next.truncated())
                && Objects.equals(previous.capturedAt(), next.capturedAt()) && previous.frames().size() == next.frames().size(), "Native 回溯身份不能改变");
        for (int index = 0; index < previous.frames().size(); index++) {
            var left = previous.frames().get(index);
            var right = next.frames().get(index);
            require(Objects.equals(left.index(), right.index()) && Objects.equals(left.moduleId(), right.moduleId())
                    && Objects.equals(left.relativePc(), right.relativePc()) && Objects.equals(left.functionName(), right.functionName())
                    && Objects.equals(left.functionOffset(), right.functionOffset()), "Native 原物理帧不能改变");
            if ("symbolicated".equals(left.resolution().status())) require(left.resolution().equals(right.resolution()), "Native 成功帧不能改变");
        }
    }

    /** 首次转换关联 Java 栈时保留采集事实与未成功原始帧。 */
    private void rawJava(NativeCrash.JavaStack raw, NativeStack.JavaStack next) {
        if (raw == null || next == null) { require(raw == null && next == null, "关联 Java 栈不能增删"); return; }
        require(Objects.equals(raw.status(), next.status()) && Objects.equals(raw.reason(), next.reason())
                && Objects.equals(raw.truncated(), next.truncated()) && Objects.equals(raw.capturedAt(), next.capturedAt()), "Java 采集状态不能改变");
        if (!"symbolicated".equals(next.symbolicationStatus())) {
            var frames = ((List<?>) next.stack().content()).stream()
                    .map(frame -> mapper.convertValue(frame, com.shanshui.apmserver.telemetry.api.StackFrame.class)).toList();
            require(raw.frames().equals(frames), "Java 原始帧不能改变");
        }
    }

    /** Java 成功后固定完整文本、来源和处理时间。 */
    private void javaStack(NativeStack.JavaStack previous, NativeStack.JavaStack next) {
        if (previous == null || next == null) { require(previous == null && next == null, "Java 栈不能增删"); return; }
        require(Objects.equals(previous.status(), next.status()) && Objects.equals(previous.reason(), next.reason())
                && Objects.equals(previous.truncated(), next.truncated()) && Objects.equals(previous.capturedAt(), next.capturedAt()), "Java 采集状态不能改变");
        if ("symbolicated".equals(previous.symbolicationStatus())) require(previous.equals(next), "Java 成功栈不能改变");
        else if (!"symbolicated".equals(next.symbolicationStatus())) require(previous.stack().equals(next.stack()), "Java 原始帧不能改变");
    }

    /** 首次转换辅助线程时核对 Java ID、系统 TID 和原始帧。 */
    private void rawAuxiliary(String rawJson, String nextJson) {
        NativeAuxiliaryStacks raw = rawJson.isEmpty() ? new NativeAuxiliaryStacks(List.of(), List.of()) : read(rawJson, NativeAuxiliaryStacks.class);
        NativeCurrentAuxiliaryStacks next = currentAuxiliary(nextJson);
        require(raw.javaThreads().size() == next.javaThreads().size() && raw.nativeThreads().size() == next.nativeThreads().size(), "辅助线程不能增删");
        for (int index = 0; index < raw.javaThreads().size(); index++) {
            var left = raw.javaThreads().get(index);
            var right = next.javaThreads().get(index);
            require(Objects.equals(left.javaId(), right.javaId()), "Java 线程身份不能改变");
            if (!"symbolicated".equals(right.stack().symbolicationStatus())) require(left.stack().equals(right.stack().stack()), "Java 原始帧不能改变");
        }
        for (int index = 0; index < raw.nativeThreads().size(); index++) {
            var left = raw.nativeThreads().get(index);
            var right = next.nativeThreads().get(index);
            require(Objects.equals(left.systemTid(), right.systemTid()), "Native 线程身份不能改变");
            rawBacktrace(left.backtrace(), right.backtrace());
        }
    }

    /** 后续轮次检查所有辅助线程及成功单元保持一致。 */
    private void auxiliary(String previousJson, String nextJson) {
        var previous = currentAuxiliary(previousJson);
        var next = currentAuxiliary(nextJson);
        require(previous.javaThreads().size() == next.javaThreads().size() && previous.nativeThreads().size() == next.nativeThreads().size(), "辅助线程不能增删");
        for (int index = 0; index < previous.javaThreads().size(); index++) {
            var left = previous.javaThreads().get(index);
            var right = next.javaThreads().get(index);
            require(Objects.equals(left.javaId(), right.javaId()), "Java 线程身份不能改变");
            javaStack(left.stack(), right.stack());
        }
        for (int index = 0; index < previous.nativeThreads().size(); index++) {
            var left = previous.nativeThreads().get(index);
            var right = next.nativeThreads().get(index);
            require(Objects.equals(left.systemTid(), right.systemTid()), "Native 线程身份不能改变");
            backtrace(left.backtrace(), right.backtrace());
        }
    }

    /** 无辅助线程与空集合保持同一语义。 */
    private NativeCurrentAuxiliaryStacks currentAuxiliary(String json) {
        return json.isEmpty() ? new NativeCurrentAuxiliaryStacks(List.of(), List.of()) : read(json, NativeCurrentAuxiliaryStacks.class);
    }

    /** 严格解析当前结构，不忽略未知字段。 */
    private <T> T read(String value, Class<T> type) {
        return mapper.readerFor(type).with(tools.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).readValue(value);
    }

    /** 只输出固定安全原因，不回显现场材料。 */
    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalArgumentException(message);
    }
}
