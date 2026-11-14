package com.shanshui.apmserver.crash.internal.application;

import com.shanshui.apmserver.crash.api.CrashThreadStack;
import com.shanshui.apmserver.crash.api.NativeAuxiliaryStacks;
import com.shanshui.apmserver.crash.api.NativeCrash;
import com.shanshui.apmserver.crash.api.NativeCurrentAuxiliaryStacks;
import com.shanshui.apmserver.crash.api.NativeStack;
import com.shanshui.apmserver.crash.internal.domain.CrashState;
import org.springframework.stereotype.Component;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

/** 将原始 Native 材料转换为保留原始身份的主辅当前栈。 */
@Component
public class NativeSnapshotPreparation {
    /** 只读取当前明确支持的结构。 */
    private final ObjectMapper mapper;

    /** 注入应用 JSON 映射器。 */
    public NativeSnapshotPreparation(ObjectMapper mapper) { this.mapper = mapper; }

    /** 已结构化状态直接使用；首次转换不改变采集材料或线程顺序。 */
    public NativeFrameBatch.Stacks prepare(CrashState state) {
        if ("native_stack".equals(state.stackFormat())) {
            var main = read(state.stackPayload(), NativeStack.class);
            var auxiliary = state.threadStacksPayload().isEmpty() ? new NativeCurrentAuxiliaryStacks(List.of(), List.of())
                    : read(state.threadStacksPayload(), NativeCurrentAuxiliaryStacks.class);
            return new NativeFrameBatch.Stacks(main, auxiliary);
        }
        if (!"raw_native".equals(state.stackFormat()) || state.stateVersion() != 0) {
            throw new IllegalArgumentException("原始 Native 基础状态无效");
        }
        var raw = read(state.stackPayload(), NativeCrash.class);
        var javaStack = raw.javaStack() == null ? null : javaStack(raw.javaStack());
        var main = new NativeStack(raw.abi(), raw.systemTid(), raw.threadName(), raw.mainThread(), raw.signal(), raw.registers(),
                raw.modules(), trace(raw.backtrace(), raw.modules()), javaStack);
        var original = state.threadStacksPayload().isEmpty() ? new NativeAuxiliaryStacks(List.of(), List.of())
                : read(state.threadStacksPayload(), NativeAuxiliaryStacks.class);
        var javaThreads = original.javaThreads().stream().map(thread -> new NativeCurrentAuxiliaryStacks.JavaThread(thread.javaId(),
                javaThread(thread))).toList();
        var nativeThreads = original.nativeThreads().stream().map(thread -> new NativeCurrentAuxiliaryStacks.NativeThread(
                thread.systemTid(), trace(thread.backtrace(), raw.modules()))).toList();
        return new NativeFrameBatch.Stacks(main, new NativeCurrentAuxiliaryStacks(javaThreads, nativeThreads));
    }

    /** 为每个原物理帧生成等待或不可处理状态，保留地址和已有函数。 */
    private NativeStack.Backtrace trace(NativeCrash.Backtrace raw, List<NativeCrash.Module> modules) {
        var frames = raw.frames().stream().map(frame -> {
            var module = modules.stream().filter(value -> value.id().equals(frame.moduleId())).findFirst().orElse(null);
            boolean reliable = module != null && module.abi() != null && module.elfBuildId() != null
                    && module.elfBuildId().matches("(?:[a-f0-9]{2}){1,64}") && frame.relativePc() != null;
            var resolution = new NativeStack.Resolution(reliable ? "pending" : "not_applicable", reliable ? "native_symbolication_deferred" : "missing_module_identity",
                    null, null, null, null, null);
            return new NativeStack.Frame(frame.index(), frame.moduleId(), frame.relativePc(), frame.functionName(), frame.functionOffset(), resolution);
        }).toList();
        return new NativeStack.Backtrace(raw.status(), raw.reason(), raw.truncated(), raw.capturedAt(), frames);
    }

    /** 崩溃线程关联 Java 栈保留采集状态与原始帧。 */
    private NativeStack.JavaStack javaStack(NativeCrash.JavaStack raw) {
        boolean empty = raw.frames().isEmpty();
        return new NativeStack.JavaStack(raw.status(), raw.reason(), raw.truncated(), raw.capturedAt(), empty ? "not_applicable" : "pending",
                empty ? "empty" : "native_symbolication_deferred", new CrashThreadStack.Stack("raw_frames", raw.frames()), null, null, null, null);
    }

    /** 其他线程采集元数据位于事实中，此处只转换独立栈。 */
    private NativeStack.JavaStack javaThread(CrashThreadStack raw) {
        boolean empty = ((List<?>) raw.stack().content()).isEmpty();
        return new NativeStack.JavaStack(null, null, null, null, empty ? "not_applicable" : "pending", empty ? "empty" : "native_symbolication_deferred",
                raw.stack(), null, null, null, null);
    }

    /** 拒绝未知字段，避免旧格式被静默接受。 */
    private <T> T read(String value, Class<T> type) {
        return mapper.readerFor(type).with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).readValue(value);
    }
}
