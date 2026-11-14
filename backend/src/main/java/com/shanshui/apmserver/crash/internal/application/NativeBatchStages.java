package com.shanshui.apmserver.crash.internal.application;

import com.shanshui.apmserver.crash.api.NativeCurrentAuxiliaryStacks;
import com.shanshui.apmserver.crash.api.NativeStack;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.UUID;

/** 使用独立预算推进同一事件的 Native 帧与 Java 栈。 */
@Component
public class NativeBatchStages {
    /** Native 地址批次。 */
    private final NativeFrameBatch frames;
    /** Java 栈批次。 */
    private final NativeJavaStackBatch javaStacks;

    /** 注入两个独立受限处理阶段。 */
    public NativeBatchStages(NativeFrameBatch frames, NativeJavaStackBatch javaStacks) {
        this.frames = frames;
        this.javaStacks = javaStacks;
    }

    /** Native 阶段失败或超时不占用 Java 阶段的预算。 */
    public NativeFrameBatch.Stacks resolve(UUID appId, String appVersion, String buildId, NativeStack main,
            NativeCurrentAuxiliaryStacks auxiliary, Duration nativeBudget, Duration javaBudget) {
        var nativeResult = frames.resolveStacks(appId, appVersion, main, auxiliary, nativeBudget);
        return javaStacks.resolve(appId, appVersion, buildId, nativeResult.main(), nativeResult.auxiliary(), javaBudget);
    }
}
