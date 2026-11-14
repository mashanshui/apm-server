package com.shanshui.apmserver.crash.internal.application;

import com.shanshui.apmserver.crash.internal.domain.CrashState;
import com.shanshui.apmserver.crash.internal.domain.CrashWorkItem;
import com.shanshui.apmserver.crash.internal.persistence.CrashSymbolicationJobs;
import com.shanshui.apmserver.crash.internal.persistence.CrashSymbolicationPublications;
import com.shanshui.apmserver.crash.internal.port.CrashStatePort;
import com.shanshui.apmserver.platform.api.QueryValidationException;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Native 后台候选分页和预算退避。 */
class NativeBackgroundProcessorTests {
    /** 上轮延期已轮转结束后，新一轮空页不能继承永久退避。 */
    @Test
    void clearsPreviousRoundDeferralWhenCursorRestarts() {
        var jobs = mock(CrashSymbolicationJobs.class);
        var states = mock(CrashStatePort.class);
        var publications = mock(CrashSymbolicationPublications.class);
        var round = mock(NativeCrashRound.class);
        UUID app = UUID.randomUUID();
        var lease = new CrashSymbolicationJobs.Lease(app, "1", UUID.randomUUID(), 3, 1, null, null, UUID.randomUUID(), "native_deferred", "native");
        when(jobs.claimNative(any(), any())).thenReturn(Optional.of(lease));
        when(publications.pendingNative(lease, 100)).thenReturn(List.of());
        when(states.nativeCandidatePage(eq(app), eq("1"), any(), any(), isNull(), isNull(), eq(100))).thenReturn(List.of());
        when(jobs.advance(lease, null, null)).thenReturn(true);
        new NativeBackgroundProcessor(jobs, states, publications, round, 100).runOnce();
        verify(jobs).release(lease, "waiting_symbols", "no_processable_source", Duration.ZERO);
    }

    /** 无来源第一页推进游标后，下一页可用事件仍会处理。 */
    @Test
    void reachesLaterPageAfterUnmatchedFirstPage() {
        var jobs = mock(CrashSymbolicationJobs.class);
        var states = mock(CrashStatePort.class);
        var publications = mock(CrashSymbolicationPublications.class);
        var round = mock(NativeCrashRound.class);
        UUID app = UUID.randomUUID(), owner = UUID.randomUUID(), jobId = UUID.randomUUID();
        Instant now = Instant.now();
        var firstLease = new CrashSymbolicationJobs.Lease(app, "1", owner, 1, 1, null, null, jobId, null, "native");
        var nextLease = new CrashSymbolicationJobs.Lease(app, "1", owner, 2, 1, now.minusSeconds(2), "one", jobId, null, "native");
        var one = new CrashStatePort.Candidate("one", "build", now.minusSeconds(2), "pending", null, null, null);
        var two = new CrashStatePort.Candidate("two", "build", now.minusSeconds(1), "pending", null, null, null);
        var first = new CrashState(app, "one", "build", one.eventTime(), 0, "pending", "native_symbolication_deferred",
                "raw_native", "{}", "SIGSEGV", "fp", "native-tmp-v1", "temporary", null, null, null, null, null, "a".repeat(64), "");
        var second = new CrashState(app, "two", "build", two.eventTime(), 0, "pending", "native_symbolication_deferred",
                "raw_native", "{}", "SIGSEGV", "fp", "native-tmp-v1", "temporary", null, null, null, null, null, "b".repeat(64), "");
        when(jobs.claimNative(any(), any())).thenReturn(Optional.of(firstLease), Optional.of(nextLease));
        when(jobs.renew(any(), any())).thenReturn(true);
        when(jobs.advance(any(), any(), anyString())).thenReturn(true);
        when(publications.pendingNative(any(), eq(1))).thenReturn(List.of());
        when(states.nativeCandidatePage(eq(app), eq("1"), any(), any(), isNull(), isNull(), eq(1))).thenReturn(List.of(one));
        when(states.nativeCandidatePage(eq(app), eq("1"), any(), any(), eq(one.eventTime()), eq("one"), eq(1))).thenReturn(List.of(two));
        when(states.nativePayloads(eq(app), eq("1"), anyList())).thenAnswer(call -> {
            List<CrashStatePort.Candidate> page = call.getArgument(2);
            return List.of(new CrashWorkItem(page.getFirst().eventId().equals("one") ? first : second, "com.example"));
        });
        when(round.processWithOutcome(any(), any(), any(), any(), any())).thenReturn(new NativeCrashRound.Outcome(false, false), new NativeCrashRound.Outcome(true, false));
        var processor = new NativeBackgroundProcessor(jobs, states, publications, round, 1);
        processor.runOnce();
        processor.runOnce();
        verify(round).processWithOutcome(eq(firstLease), eq(first), any(), any(), any());
        verify(round).processWithOutcome(eq(nextLease), eq(second), any(), any(), any());
        verify(jobs).advance(nextLease, two.eventTime(), "two");
    }

    /** 载荷超预算时减半，前部无来源不阻挡后续事件。 */
    @Test
    void halvesPayloadsAndAdvancesPastUnprocessableEvents() {
        var jobs = mock(CrashSymbolicationJobs.class);
        var states = mock(CrashStatePort.class);
        var publications = mock(CrashSymbolicationPublications.class);
        var round = mock(NativeCrashRound.class);
        UUID app = UUID.randomUUID();
        Instant now = Instant.now();
        var lease = new CrashSymbolicationJobs.Lease(app, "1", UUID.randomUUID(), 1, 1, null, null, UUID.randomUUID(), null, "native");
        var one = new CrashStatePort.Candidate("one", "build", now.minusSeconds(2), "pending", null, null, null);
        var two = new CrashStatePort.Candidate("two", "build", now.minusSeconds(1), "pending", null, null, null);
        var first = new CrashState(app, "one", "build", one.eventTime(), 0, "pending", "native_symbolication_deferred",
                "raw_native", "{}", "SIGSEGV", "fp", "native-tmp-v1", "temporary", null, null, null, null, null, "a".repeat(64), "");
        var second = new CrashState(app, "two", "build", two.eventTime(), 0, "pending", "native_symbolication_deferred",
                "raw_native", "{}", "SIGSEGV", "fp", "native-tmp-v1", "temporary", null, null, null, null, null, "b".repeat(64), "");
        when(jobs.claimNative(any(), any())).thenReturn(Optional.of(lease));
        when(jobs.renew(eq(lease), any())).thenReturn(true);
        when(jobs.advance(eq(lease), any(), anyString())).thenReturn(true);
        when(publications.pendingNative(lease, 100)).thenReturn(List.of());
        when(states.nativeCandidatePage(eq(app), eq("1"), any(), any(), isNull(), isNull(), eq(100))).thenReturn(List.of(one, two));
        when(states.nativePayloads(eq(app), eq("1"), anyList())).thenAnswer(call -> {
            List<CrashStatePort.Candidate> page = call.getArgument(2);
            if (page.size() > 1) throw new QueryValidationException("QUERY_RESOURCE_LIMIT", "limit", 503);
            return List.of(new CrashWorkItem(page.getFirst().eventId().equals("one") ? first : second, "com.example"));
        });
        when(round.processWithOutcome(eq(lease), any(), any(), any(), any())).thenReturn(new NativeCrashRound.Outcome(false, false), new NativeCrashRound.Outcome(true, false));
        new NativeBackgroundProcessor(jobs, states, publications, round, 100).runOnce();
        verify(states, times(3)).nativePayloads(eq(app), eq("1"), anyList());
        verify(round).processWithOutcome(eq(lease), eq(first), any(), any(), any());
        verify(round).processWithOutcome(eq(lease), eq(second), any(), any(), any());
        verify(jobs).advance(lease, two.eventTime(), two.eventId());
        verify(jobs).release(lease, "queued", null, Duration.ZERO);
    }

    /** 载荷加载接近期限时只分配真实剩余时间，过期事件不推进游标。 */
    @Test
    void limitsNativeBudgetToRemainingRoundAndDefersUnstartedEvent() {
        for (long elapsed : List.of(Duration.ofMillis(29_900).toNanos(), Duration.ofSeconds(30).toNanos())) {
            var jobs = mock(CrashSymbolicationJobs.class);
            var states = mock(CrashStatePort.class);
            var publications = mock(CrashSymbolicationPublications.class);
            var round = mock(NativeCrashRound.class);
            var clock = new AtomicLong();
            UUID app = UUID.randomUUID();
            Instant now = Instant.now();
            var lease = new CrashSymbolicationJobs.Lease(app, "1", UUID.randomUUID(), 1, 1, null, null, UUID.randomUUID(), null, "native");
            var candidate = new CrashStatePort.Candidate("event", "build", now, "pending", null, null, null);
            var state = new CrashState(app, "event", "build", now, 0, "pending", "native_symbolication_deferred",
                    "raw_native", "{}", "SIGSEGV", "fp", "native-tmp-v1", "temporary", null, null, null, null, null, "a".repeat(64), "");
            when(jobs.claimNative(any(), any())).thenReturn(Optional.of(lease));
            when(jobs.renew(eq(lease), any())).thenReturn(true);
            when(jobs.advance(eq(lease), any(), anyString())).thenReturn(true);
            when(publications.pendingNative(lease, 1)).thenReturn(List.of());
            when(states.nativeCandidatePage(eq(app), eq("1"), any(), any(), isNull(), isNull(), eq(1))).thenReturn(List.of(candidate));
            when(states.nativePayloads(eq(app), eq("1"), anyList())).thenAnswer(call -> {
                clock.set(elapsed);
                return List.of(new CrashWorkItem(state, "com.example"));
            });
            when(round.processWithOutcome(eq(lease), eq(state), any(), any(), any())).thenReturn(new NativeCrashRound.Outcome(false, false));
            new NativeBackgroundProcessor(jobs, states, publications, round, 1, clock::get).runOnce();
            if (elapsed < Duration.ofSeconds(30).toNanos()) {
                verify(round).processWithOutcome(eq(lease), eq(state), eq(Duration.ofMillis(100)), eq(Duration.ofSeconds(2)), any());
                verify(jobs).advance(lease, now, "event");
            } else {
                verify(round, never()).processWithOutcome(any(), any(), any(), any(), any());
                verify(jobs, never()).advance(any(), any(), anyString());
            }
            verify(jobs).release(lease, "queued", null, Duration.ZERO);
        }
    }
}
