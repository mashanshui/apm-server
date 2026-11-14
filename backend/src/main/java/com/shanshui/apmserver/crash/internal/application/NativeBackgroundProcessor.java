package com.shanshui.apmserver.crash.internal.application;

import com.shanshui.apmserver.crash.internal.domain.CrashWorkItem;
import com.shanshui.apmserver.crash.internal.persistence.CrashSymbolicationJobs;
import com.shanshui.apmserver.crash.internal.persistence.CrashSymbolicationPublications;
import com.shanshui.apmserver.crash.internal.port.CrashStatePort;
import com.shanshui.apmserver.platform.api.QueryValidationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import java.util.function.LongSupplier;

/** Native 版本工作使用有限候选页及可恢复的整快照发布。 */
@Service
public class NativeBackgroundProcessor {
    /** 只记录安全阶段和计数。 */
    private static final Logger LOG = LoggerFactory.getLogger(NativeBackgroundProcessor.class);
    /** 类型隔离的版本租约。 */
    private final CrashSymbolicationJobs jobs;
    /** 分页身份与有限载荷。 */
    private final CrashStatePort states;
    /** 未确认载荷优先恢复。 */
    private final CrashSymbolicationPublications publications;
    /** 单事件完整快照处理。 */
    private final NativeCrashRound round;
    /** 单次最多处理的候选数。 */
    private final int batchSize;
    /** 单调时钟用于计算本轮剩余处理时间。 */
    private final LongSupplier nanoTime;
    /** 当前进程的稳定执行身份。 */
    private final UUID owner = UUID.randomUUID();
    /** 关闭时停止开始新事件。 */
    private volatile boolean stopping;

    /** 注入管理库、遥测查询和有限批次参数。 */
    @Autowired
    public NativeBackgroundProcessor(CrashSymbolicationJobs jobs, CrashStatePort states,
            CrashSymbolicationPublications publications, NativeCrashRound round,
            @Value("${apm.crash.background.batch-size:100}") int batchSize) {
        this(jobs, states, publications, round, batchSize, System::nanoTime);
    }

    /** 注入可控单调时钟，以验证候选接近期限时的预算。 */
    NativeBackgroundProcessor(CrashSymbolicationJobs jobs, CrashStatePort states,
            CrashSymbolicationPublications publications, NativeCrashRound round, int batchSize, LongSupplier nanoTime) {
        if (batchSize < 1 || batchSize > 100) throw new IllegalArgumentException("Native 后台批次超限");
        this.jobs = jobs;
        this.states = states;
        this.publications = publications;
        this.round = round;
        this.batchSize = batchSize;
        this.nanoTime = nanoTime;
    }

    /** 一次最多领取一个版本；异常保留在途载荷并退避。 */
    public synchronized void runOnce() {
        if (stopping) return;
        var claimed = jobs.claimNative(owner, Duration.ofMinutes(1));
        if (claimed.isEmpty()) return;
        var lease = claimed.get();
        try {
            process(lease);
        } catch (RuntimeException failure) {
            LOG.warn("Native 版本工作暂不可用，稍后重试");
            jobs.release(lease, "retry_wait", "processing_unavailable", Duration.ofSeconds(30));
        }
    }

    /** 重放在途载荷后读取一页，并仅推进已完成的连续前缀。 */
    private void process(CrashSymbolicationJobs.Lease lease) {
        for (var publication : publications.pendingNative(lease, batchSize)) {
            if (stopping || !publications.publish(lease, publication)) return;
        }
        Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        var page = states.nativeCandidatePage(lease.appId(), lease.appVersion(), now.minus(Duration.ofDays(30)), now,
                lease.scanTime(), lease.scanEventId(), batchSize);
        // 延期只在同一轮跨页累计；游标重置后必须重新计算，避免已恢复任务永久退避。
        boolean retry = lease.scanTime() != null && "native_deferred".equals(lease.scanError());
        if (page.isEmpty()) {
            if (!jobs.advance(lease, null, null)) return;
            jobs.release(lease, retry ? "retry_wait" : "waiting_symbols", retry ? "native_deferred" : "no_processable_source",
                    retry ? Duration.ofSeconds(30) : Duration.ZERO);
            return;
        }
        long deadline = nanoTime.getAsLong() + Duration.ofSeconds(30).toNanos();
        int offset = 0;
        while (offset < page.size()) {
            if (stopping || deadline - nanoTime.getAsLong() <= 0) {
                jobs.release(lease, "queued", retry ? "native_deferred" : null, Duration.ZERO);
                return;
            }
            if (!jobs.renew(lease, Duration.ofMinutes(1))) return;
            var chunk = load(lease, page.subList(offset, page.size()));
            retry |= chunk.deferred();
            for (var candidate : page.subList(offset, offset + chunk.consumed())) {
                if (stopping || deadline - nanoTime.getAsLong() <= 0) {
                    jobs.release(lease, "queued", retry ? "native_deferred" : null, Duration.ZERO);
                    return;
                }
                var item = chunk.items().stream().filter(value -> value.state().eventId().equals(candidate.eventId())).findFirst();
                if (item.isPresent()) {
                    // 载荷查询及候选选择也消耗本轮时间，开始事件前重新核算。
                    long remaining = deadline - nanoTime.getAsLong();
                    if (remaining <= 0) {
                        LOG.debug("Native 版本工作已到期限，剩余事件等待下一轮");
                        jobs.release(lease, "queued", retry ? "native_deferred" : null, Duration.ZERO);
                        return;
                    }
                    Duration nativeBudget = Duration.ofNanos(Math.min(Duration.ofSeconds(2).toNanos(), remaining));
                    retry |= round.processWithOutcome(lease, item.get().state(), nativeBudget, Duration.ofSeconds(2),
                            () -> deadline - nanoTime.getAsLong()).retry();
                }
                if (!jobs.advance(lease, candidate.eventTime(), candidate.eventId())) return;
            }
            offset += chunk.consumed();
        }
        LOG.info("Native 候选页处理完成，数量={}，存在延期={}", page.size(), retry);
        jobs.release(lease, "queued", retry ? "native_deferred" : null, Duration.ZERO);
    }

    /** 响应预算不足时逐次缩小载荷批次，单条超限保留材料并等待下一轮。 */
    private Chunk load(CrashSymbolicationJobs.Lease lease, List<CrashStatePort.Candidate> candidates) {
        int count = candidates.size();
        while (true) {
            try {
                return new Chunk(states.nativePayloads(lease.appId(), lease.appVersion(), candidates.subList(0, count)), count, false);
            } catch (QueryValidationException failure) {
                if (!java.util.Set.of("QUERY_RESOURCE_LIMIT", "QUERY_TIMEOUT").contains(failure.getCode())) throw failure;
                if (count == 1) return new Chunk(List.of(), 1, true);
                count = Math.max(1, count / 2);
                if (!jobs.renew(lease, Duration.ofMinutes(1))) throw failure;
            }
        }
    }

    /** 分批结果、候选消费数量和资源延期标记。 */
    private record Chunk(/** 当前有限载荷。 */ List<CrashWorkItem> items, /** 已消费候选数量。 */ int consumed,
                         /** 单条载荷是否暂超预算。 */ boolean deferred) { }

    /** 关闭时不再启动新的事件处理。 */
    @jakarta.annotation.PreDestroy
    public void stop() { stopping = true; }
}
