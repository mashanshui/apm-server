package com.shanshui.apmserver.crash.internal.application;

import com.shanshui.apmserver.crash.api.NativeCurrentAuxiliaryStacks;
import com.shanshui.apmserver.crash.api.NativeStack;
import com.shanshui.apmserver.crash.internal.domain.CrashResultTooLargeException;
import com.shanshui.apmserver.crash.internal.domain.CrashState;
import com.shanshui.apmserver.crash.internal.persistence.CrashSymbolicationJobs;
import com.shanshui.apmserver.crash.internal.persistence.CrashSymbolicationPublications;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 一轮只能通过完整快照发布，超限不覆盖旧成功。 */
class NativeCrashRoundTests {
    /** 新快照超限时保留已确认内容且不调用发布。 */
    @Test
    void retainsConfirmedStateWhenNewSnapshotIsTooLarge() {
        var preparation = mock(NativeSnapshotPreparation.class);
        var stages = mock(NativeBatchStages.class);
        var summary = mock(NativeSnapshotSummary.class);
        var publications = mock(CrashSymbolicationPublications.class);
        UUID app = UUID.randomUUID();
        Instant now = Instant.parse("2026-01-01T00:00:00Z");
        var source = new NativeStack.Source("NATIVE_SO", UUID.randomUUID(), 1, "b".repeat(64));
        var resolved = new NativeStack.Resolution("symbolicated", null, List.of(new NativeStack.Inline("f", null, null, null)), source, source, now, null);
        var main = new NativeStack("arm64-v8a", 1, null, true, null, null,
                List.of(new com.shanshui.apmserver.crash.api.NativeCrash.Module("m", "lib.so", null, "arm64-v8a", "abcd")),
                new NativeStack.Backtrace("ok", null, false, 1L, List.of(new NativeStack.Frame(0, "m", "0x1", null, null, resolved))), null);
        var base = new CrashState(app, "event", "build", now, 1, "symbolicated", null, "native_stack",
                new tools.jackson.databind.ObjectMapper().writeValueAsString(main), "SIGSEGV", "fp", "native-tmp-v1", "temporary",
                null, null, null, now, null, "a".repeat(64), "", true);
        var aux = new NativeCurrentAuxiliaryStacks(List.of(), List.of());
        var stacks = new NativeFrameBatch.Stacks(main, aux);
        var lease = mock(CrashSymbolicationJobs.Lease.class);
        when(lease.appVersion()).thenReturn("1");
        when(publications.existing(app, "event")).thenReturn(Optional.empty());
        when(preparation.prepare(base)).thenReturn(stacks);
        when(stages.resolve(any(), anyString(), anyString(), any(), any(), any(), any())).thenReturn(stacks);
        when(summary.summarize(base, main, aux)).thenReturn(new NativeSnapshotSummary.Result(base, 0, 0, 0, 0));
        when(publications.registerNative(eq(lease), eq(base), eq(base), anyLong())).thenThrow(new CrashResultTooLargeException());
        assertFalse(new NativeCrashRound(preparation, stages, summary, publications).process(lease, base, Duration.ofSeconds(1), Duration.ofSeconds(1)));
        verify(publications, never()).publish(any(), any());
    }

    /** 事件准备耗尽本轮剩余时间后，Native 不开始，Java 预算仍独立。 */
    @Test
    void checksRemainingTimeAfterPreparationWithoutConsumingJavaBudget() {
        var preparation = mock(NativeSnapshotPreparation.class);
        var stages = mock(NativeBatchStages.class);
        var summary = mock(NativeSnapshotSummary.class);
        var publications = mock(CrashSymbolicationPublications.class);
        UUID app = UUID.randomUUID();
        Instant now = Instant.parse("2026-01-01T00:00:00Z");
        var main = new NativeStack("arm64-v8a", 1, null, true, null, null, List.of(),
                new NativeStack.Backtrace("ok", null, false, 1L, List.of()), null);
        var aux = new NativeCurrentAuxiliaryStacks(List.of(), List.of());
        var stacks = new NativeFrameBatch.Stacks(main, aux);
        var base = new CrashState(app, "event", "build", now, 0, "pending", "native_symbolication_deferred", "raw_native", "{}",
                "SIGSEGV", "fp", "native-tmp-v1", "temporary", null, null, null, null, null, "a".repeat(64), "");
        var lease = mock(CrashSymbolicationJobs.Lease.class);
        when(lease.appVersion()).thenReturn("1");
        when(publications.existing(app, "event")).thenReturn(Optional.empty());
        when(preparation.prepare(base)).thenReturn(stacks);
        when(stages.resolve(app, "1", "build", main, aux, Duration.ZERO, Duration.ofSeconds(2))).thenReturn(stacks);
        when(summary.summarize(base, main, aux)).thenReturn(new NativeSnapshotSummary.Result(base, 0, 0, 0, 0));
        when(publications.registerNative(eq(lease), eq(base), eq(base), anyLong())).thenReturn(Optional.empty());
        new NativeCrashRound(preparation, stages, summary, publications)
                .processWithOutcome(lease, base, Duration.ofMillis(100), Duration.ofSeconds(2), () -> -1);
        verify(stages).resolve(app, "1", "build", main, aux, Duration.ZERO, Duration.ofSeconds(2));
    }
}
