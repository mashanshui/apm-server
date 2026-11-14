package com.shanshui.apmserver.crash.internal.application;

import com.shanshui.apmserver.crash.api.NativeStack;
import com.shanshui.apmserver.crash.internal.domain.CrashResultTooLargeException;
import com.shanshui.apmserver.crash.internal.domain.CrashState;
import com.shanshui.apmserver.crash.internal.persistence.CrashSymbolicationJobs;
import com.shanshui.apmserver.crash.internal.persistence.CrashSymbolicationPublications;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.LongSupplier;

/** 以完整快照执行单个 Native 事件的一轮处理。 */
@Component
public class NativeCrashRound {
    /** 日志只记录安全阶段和结果。 */
    private static final Logger LOG = LoggerFactory.getLogger(NativeCrashRound.class);
    /** 原始与结构化状态转换。 */
    private final NativeSnapshotPreparation preparation;
    /** Native 和 Java 独立预算。 */
    private final NativeBatchStages stages;
    /** 主栈摘要与剩余工作计算。 */
    private final NativeSnapshotSummary summary;
    /** 持久登记及同字节发布。 */
    private final CrashSymbolicationPublications publications;

    /** 注入已有状态和发布边界。 */
    public NativeCrashRound(NativeSnapshotPreparation preparation, NativeBatchStages stages,
                            NativeSnapshotSummary summary, CrashSymbolicationPublications publications) {
        this.preparation = preparation;
        this.stages = stages;
        this.summary = summary;
        this.publications = publications;
    }

    /** 待确认快照先重放；实际变化才登记并读回确认。 */
    public boolean process(CrashSymbolicationJobs.Lease lease, CrashState base, Duration nativeBudget, Duration javaBudget) {
        return processWithOutcome(lease, base, nativeBudget, javaBudget).published();
    }

    /** 反馈是否存在暂时性延期，供版本任务在页末退避。 */
    public Outcome processWithOutcome(CrashSymbolicationJobs.Lease lease, CrashState base, Duration nativeBudget, Duration javaBudget) {
        return processWithOutcome(lease, base, nativeBudget, javaBudget, nativeBudget::toNanos);
    }

    /** 准备完成后重新限制 Native 预算，Java 仍使用独立预算。 */
    public Outcome processWithOutcome(CrashSymbolicationJobs.Lease lease, CrashState base, Duration nativeBudget,
                                      Duration javaBudget, LongSupplier remainingNanos) {
        var previous = publications.existing(base.appId(), base.eventId());
        if (previous.isPresent() && previous.get().payload() != null) return new Outcome(publications.publish(lease, previous.get()), true);
        var initial = preparation.prepare(base);
        long available = Math.max(0, Math.min(nativeBudget.toNanos(), remainingNanos.getAsLong()));
        var updated = stages.resolve(base.appId(), lease.appVersion(), base.buildId(), initial.main(), initial.auxiliary(), Duration.ofNanos(available), javaBudget);
        var candidate = summary.summarize(base, updated.main(), updated.auxiliary()).state();
        long failedUnits = newFailures(initial, updated);
        try {
            var publication = publications.registerNative(lease, base, candidate, failedUnits);
            if (publication.isEmpty()) return new Outcome(false, candidate.retryAt() != null || !candidate.equals(base));
            return new Outcome(publications.publish(lease, publication.get()), candidate.retryAt() != null);
        } catch (CrashResultTooLargeException failure) {
            LOG.warn("Native 完整快照超限，保留已确认状态");
            return new Outcome(false, true);
        }
    }

    /** 本轮发布和临时延期结果。 */
    public record Outcome(/** 是否确认发布。 */ boolean published, /** 是否需要退避后重试。 */ boolean retry) { }

    /** 只统计本轮发生实际尝试且未成功的单元，不重复累计旧失败。 */
    private long newFailures(NativeFrameBatch.Stacks before, NativeFrameBatch.Stacks after) {
        var oldFrames = frames(before);
        var newFrames = frames(after);
        long count = 0;
        for (int index = 0; index < oldFrames.size(); index++) {
            var old = oldFrames.get(index).resolution();
            var next = newFrames.get(index).resolution();
            if (!"symbolicated".equals(next.status()) && next.processedAt() != null
                    && !Objects.equals(old.processedAt(), next.processedAt())) count++;
        }
        var oldJava = javaStacks(before);
        var newJava = javaStacks(after);
        for (int index = 0; index < oldJava.size(); index++) {
            var old = oldJava.get(index);
            var next = newJava.get(index);
            if (!"symbolicated".equals(next.symbolicationStatus()) && next.processedAt() != null
                    && !Objects.equals(old.processedAt(), next.processedAt())) count++;
        }
        return count;
    }

    /** 按主栈和线程顺序平铺物理帧，不改变其身份。 */
    private List<NativeStack.Frame> frames(NativeFrameBatch.Stacks value) {
        var frames = new ArrayList<>(value.main().backtrace().frames());
        value.auxiliary().nativeThreads().forEach(thread -> frames.addAll(thread.backtrace().frames()));
        return frames;
    }

    /** 按主关联栈和 Java ID 原顺序平铺独立 Java 栈。 */
    private List<NativeStack.JavaStack> javaStacks(NativeFrameBatch.Stacks value) {
        var stacks = new ArrayList<NativeStack.JavaStack>();
        if (value.main().javaStack() != null) stacks.add(value.main().javaStack());
        value.auxiliary().javaThreads().forEach(thread -> stacks.add(thread.stack()));
        return stacks;
    }
}
