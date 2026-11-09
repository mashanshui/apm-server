package com.shanshui.apmserver.crash.internal.application;

import com.shanshui.apmserver.crash.api.CrashPayload;
import com.shanshui.apmserver.crash.internal.domain.CrashState;
import com.shanshui.apmserver.crash.internal.domain.CrashResultTooLargeException;
import com.shanshui.apmserver.crash.internal.domain.CrashWorkItem;
import com.shanshui.apmserver.platform.api.QueryValidationException;
import com.shanshui.apmserver.crash.internal.persistence.CrashSymbolicationJobs;
import com.shanshui.apmserver.crash.internal.persistence.CrashSymbolicationPublications;
import com.shanshui.apmserver.crash.internal.port.CrashStatePort;
import com.shanshui.apmserver.symbol.api.SymbolFileLease;
import com.shanshui.apmserver.symbol.api.SymbolRegistry;
import com.shanshui.apmserver.symbol.api.SymbolicationResult;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import java.util.List;

/** 一次执行一个有界构建批次；请求线程只登记工作，不调用本执行器。 */
@Service
public class CrashBackgroundProcessor {
    /** 仅记录安全阶段与状态，不输出堆栈或设备身份。 */
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(CrashBackgroundProcessor.class);
    /** 构建领取、唤醒与进展。 */
    private final CrashSymbolicationJobs jobs;
    /** 最新状态和有限候选。 */
    private final CrashStatePort states;
    /** 首次成功与同字节恢复。 */
    private final CrashSymbolicationPublications publications;
    /** 统一文件租约与 R8 许可。 */
    private final SymbolRegistry symbols;
    /** 正式指纹算法。 */
    private final CrashFingerprintService fingerprints;
    /** 完整结果脱敏。 */
    private final CrashSanitizer sanitizer;
    /** 原始链 JSON 解码。 */
    private final ObjectMapper mapper;
    /** 主异常成功后的有界辅助线程处理。 */
    private final CrashAuxiliaryStacks auxiliary;
    /** 一次最多读取的事件数。 */
    private final int batchSize;
    /** 独立进程身份，只参与租约，不写入遥测。 */
    private final UUID owner = UUID.randomUUID();
    /** 停止后不开始新的计算或发布。 */
    private volatile boolean stopping;

    /** 注入当前模型和有限批次配置。 */
    public CrashBackgroundProcessor(CrashSymbolicationJobs jobs, CrashStatePort states,
            CrashSymbolicationPublications publications, SymbolRegistry symbols, CrashFingerprintService fingerprints,
            CrashSanitizer sanitizer, ObjectMapper mapper,
            @Value("${apm.crash.background.batch-size:100}") int batchSize) {
        this.jobs = jobs;
        this.states = states;
        this.publications = publications;
        this.symbols = symbols;
        this.fingerprints = fingerprints;
        this.sanitizer = sanitizer;
        this.mapper = mapper;
        this.auxiliary = new CrashAuxiliaryStacks(mapper, symbols, sanitizer);
        this.batchSize = batchSize;
        if (batchSize < 1 || batchSize > 100) throw new IllegalArgumentException("后台批次必须介于 1 到 100");
    }

    /** 调度和隔离验收共用入口；同步门禁确保同一实例最多一个在途构建。 */
    public synchronized void runOnce() {
        if (stopping) return;
        // 不把任务放入内存队列，数据库一次只领取一个构建。
        var claimed = jobs.claim(owner, Duration.ofMinutes(1));
        if (claimed.isEmpty()) return;
        var lease = claimed.get();
        try {
            process(lease);
        } catch (RuntimeException failure) {
            // 无法确认的存储写入保留登记载荷；不记录原始异常或文件路径。
            jobs.release(lease, "retry_wait", "processing_unavailable", Duration.ofSeconds(30));
        }
    }

    /** 同一批次固定文件租约，下一批重新获取当前活动文件。 */
    private void process(CrashSymbolicationJobs.Lease lease) {
        // 即使 mapping 已被删除，已登记的首次成功也必须先恢复发布。
        for (var publication : publications.pending(lease, batchSize)) {
            if (stopping || !publications.publish(lease, publication)) return;
        }
        if (stopping) {
            jobs.release(lease, "queued", null, Duration.ZERO);
            return;
        }
        var acquired = symbols.acquire(lease.appId(), lease.buildId());
        if (acquired.isEmpty()) {
            jobs.release(lease, "waiting_mapping", "mapping_missing", Duration.ZERO);
            return;
        }
        try (SymbolFileLease mapping = acquired.get()) {
            // 数据库查询范围和行数有硬上限；载荷仅在有限身份筛选后读取。
            Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
            var batch = loadCandidates(lease, mapping, now);
            var candidates = batch.items();
            long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
            boolean retry = false;
            for (var work : candidates) {
                // 包名来自与候选状态关联的事实，同批只持有有限事件。
                CrashState candidate = work.state();
                if (stopping || System.nanoTime() >= deadline) {
                    jobs.release(lease, "queued", null, Duration.ZERO);
                    return;
                }
                if (!jobs.renew(lease, Duration.ofMinutes(1))) return;
                // 登记结果可能尚未反映在候选状态中，绝不因此再次选择 mapping 计算。
                var existing = publications.existing(lease.appId(), candidate.eventId());
                if (existing.isPresent()) {
                    if (!publications.publish(lease, existing.get())) return;
                    continue;
                }
                SymbolicationResult result;
                try {
                    // 未知格式不能当作原始链解释，确定性失败保留该条材料并继续其他事件。
                    if (!"raw_chain".equals(candidate.stackFormat())) throw new IllegalArgumentException("非法当前状态");
                    CrashPayload raw = mapper.readValue(candidate.stackPayload(), CrashPayload.class);
                    if (raw == null || raw.throwableChain() == null || raw.throwableChain().isEmpty()
                            || raw.throwableChain().stream().anyMatch(node -> node == null || node.frames() == null || node.frames().isEmpty())) {
                        throw new IllegalArgumentException("原始异常链不完整");
                    }
                    result = symbols.retrace(mapping, CrashStackTraceFormatter.lines(raw));
                    if (result == null || (result.status() == SymbolicationResult.Status.SYMBOLICATED
                            && (result.text() == null || result.text().isBlank()))) result = SymbolicationResult.failed("empty_output");
                } catch (RuntimeException invalid) {
                    result = SymbolicationResult.failed("invalid_stack");
                }
                if (result.status() == SymbolicationResult.Status.SYMBOLICATED) {
                    String safeText = sanitizer.sanitizeRetrace(result.text());
                    var normalized = new SymbolicationResult(safeText, SymbolicationResult.Status.SYMBOLICATED, null).fingerprintInput();
                    String exception = normalized.exceptions().isEmpty() ? candidate.exceptionType()
                            : normalized.exceptions().getFirst().split(":", 2)[0];
                    String auxiliaryStacks = auxiliary.resolve(candidate.threadStacksPayload(), mapping);
                    CrashState computed = new CrashState(candidate.appId(), candidate.eventId(), candidate.buildId(),
                            candidate.eventTime(), 0, "symbolicated", null, "retrace_text", safeText, exception,
                            fingerprints.resolved(candidate.appId(), work.packageName(), normalized), "resolved-v1", "resolved",
                            mapping.symbolId(), mapping.revision(), mapping.sha256(), Instant.now().truncatedTo(ChronoUnit.MILLIS), null,
                            candidate.captureSha256(), auxiliaryStacks);
                    try {
                        var fixed = registerWithAuxiliaryFallback(lease, computed, candidate);
                        if (fixed.isEmpty() || stopping || !publications.publish(lease, fixed.get())) return;
                        log.info("Crash 后台结果已确认，包含辅助线程={}", !auxiliaryStacks.isEmpty());
                    } catch (CrashResultTooLargeException tooLarge) {
                        // 不截断文本来伪造成功，也不在同一 mapping 下无限重试超限结果。
                        if (!recordFailure(lease, mapping, candidate, "output_limit", false)) return;
                        log.warn("Crash 后台结果超过发布上限，已保留原始堆栈");
                    }
                } else {
                    // 许可不足属于临时失败；确定性解析/输出错误等待下一份 mapping。
                    boolean transientFailure = "parser_busy".equals(result.reason()) || "mapping_unavailable".equals(result.reason());
                    retry |= transientFailure;
                    if (!recordFailure(lease, mapping, candidate, result.reason(), transientFailure)) return;
                }
            }
            jobs.release(lease, candidates.size() == batch.limit() ? "queued" : retry ? "retry_wait" : "idle",
                    retry ? "transient_event_failure" : null, retry ? Duration.ofSeconds(30) : Duration.ZERO);
        } catch (IOException failure) {
            jobs.release(lease, "retry_wait", "mapping_unavailable", Duration.ofSeconds(30));
        }
    }

    /** 单份发布超限时先将辅助还原结果回退为原始帧，再登记同一主异常成功结果。 */
    private java.util.Optional<CrashSymbolicationPublications.Publication> registerWithAuxiliaryFallback(
            CrashSymbolicationJobs.Lease lease, CrashState computed, CrashState original) {
        try {
            return publications.register(lease, computed);
        } catch (CrashResultTooLargeException tooLarge) {
            String fallback = auxiliary.fallback(original.threadStacksPayload());
            if (fallback.equals(computed.threadStacksPayload())) throw tooLarge;
            CrashState reduced = new CrashState(computed.appId(), computed.eventId(), computed.buildId(), computed.eventTime(),
                    computed.stateVersion(), computed.status(), computed.reason(), computed.stackFormat(), computed.stackPayload(),
                    computed.exceptionType(), computed.fingerprint(), computed.fingerprintVersion(), computed.groupingKind(),
                    computed.symbolId(), computed.symbolRevision(), computed.symbolSha256(), computed.processedAt(), computed.retryAt(),
                    computed.captureSha256(), fallback);
            return publications.register(lease, reduced);
        }
    }

    /** 大载荷响应超过预算时有限减半，单事件仍不满足预算则延期而不宣称完成。 */
    private CandidateBatch loadCandidates(CrashSymbolicationJobs.Lease lease, SymbolFileLease mapping, Instant now) {
        int limit = batchSize;
        while (true) {
            try {
                return new CandidateBatch(states.candidates(lease.appId(), lease.buildId(), mapping.symbolId(), mapping.revision(),
                        now.minus(Duration.ofDays(30)), now, limit), limit);
            } catch (QueryValidationException exceeded) {
                if (limit == 1 || !("QUERY_RESOURCE_LIMIT".equals(exceeded.getCode()) || "QUERY_TIMEOUT".equals(exceeded.getCode()))) throw exceeded;
                if (stopping || !jobs.renew(lease, Duration.ofMinutes(1))) throw exceeded;
                limit = Math.max(1, limit / 2);
            }
        }
    }

    /** 本批实际查询上限用于判断是否立即继续，不把缩小批次误判为全部完成。 */
    private record CandidateBatch(/** 有限候选。 */ List<CrashWorkItem> items, /** 实际上限。 */ int limit) {}

    /** 单条失败保留当前原始材料；首次成功已登记时不再分配失败版本。 */
    private boolean recordFailure(CrashSymbolicationJobs.Lease lease, SymbolFileLease mapping,
                                  CrashState candidate, String reason, boolean transientFailure) {
        var version = publications.failureVersion(lease, candidate.eventId());
        if (version.isEmpty()) return true;
        CrashState failed = new CrashState(candidate.appId(), candidate.eventId(), candidate.buildId(),
                candidate.eventTime(), version.get(), "failed", reason, "raw_chain", candidate.stackPayload(),
                candidate.exceptionType(), candidate.fingerprint(), candidate.fingerprintVersion(), "temporary",
                mapping.symbolId(), mapping.revision(), mapping.sha256(), Instant.now().truncatedTo(ChronoUnit.MILLIS),
                transientFailure ? Instant.now().plusSeconds(30).truncatedTo(ChronoUnit.MILLIS) : null,
                candidate.captureSha256(), candidate.threadStacksPayload());
        if (stopping || !jobs.renew(lease, Duration.ofMinutes(1))) return false;
        states.publish(failed);
        return true;
    }

    /** 正常关闭只停止新工作，已登记结果由后续租约恢复，不伪造完成。 */
    @PreDestroy
    public void stop() { stopping = true; }
}
