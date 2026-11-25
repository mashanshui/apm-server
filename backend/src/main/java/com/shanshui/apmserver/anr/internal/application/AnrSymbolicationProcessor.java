package com.shanshui.apmserver.anr.internal.application;

import com.shanshui.apmserver.anr.api.AnrJavaView;
import com.shanshui.apmserver.anr.internal.domain.AnrEvent;
import com.shanshui.apmserver.anr.internal.persistence.AnrSymbolicationPublications;
import com.shanshui.apmserver.anr.internal.port.AnrRepository;
import com.shanshui.apmserver.anr.internal.port.AnrSymbolicationStore;
import com.shanshui.apmserver.symbol.api.SymbolBuildIndex;
import com.shanshui.apmserver.symbol.api.SymbolFileLease;
import com.shanshui.apmserver.symbol.api.SymbolRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** 单工作者执行固定来源的 ANR Java 还原及有限版本轮转。 */
@Component
public class AnrSymbolicationProcessor {
    /** 不记录原始现场或 mapping 内容。 */
    private static final Logger LOG = LoggerFactory.getLogger(AnrSymbolicationProcessor.class);
    /** 单次扫描候选数量。 */
    private static final int PAGE_SIZE = 20;
    /** 临时失败后的最短重试间隔。 */
    private static final Duration RETRY = Duration.ofSeconds(30);
    /** 原始事件存储。 */
    private final AnrRepository events;
    /** 单分区当前状态。 */
    private final AnrSymbolicationStore states;
    /** 固定结果和发布恢复。 */
    private final AnrSymbolicationPublications publications;
    /** 版本化 mapping 读取入口。 */
    private final SymbolRegistry symbols;
    /** 活动 mapping 的有限轮转入口。 */
    private final SymbolBuildIndex builds;
    /** 有界栈帧适配。 */
    private final AnrJavaRetrace retrace;
    /** 最近入库的有限唤醒。 */
    private final AnrSymbolicationWakeup wakeup;
    /** 持久化每个应用版本的扫描进度。 */
    private final JdbcTemplate jdbc;
    /** 活动符号索引的单个轮转位置。 */
    private UUID afterSymbol;
    /** 全局存储暂时失败后的单调时钟截止时间。 */
    private long retryAfterNanos;

    /** 装配后台处理边界。 */
    public AnrSymbolicationProcessor(AnrRepository events, AnrSymbolicationStore states,
                                     AnrSymbolicationPublications publications, SymbolRegistry symbols,
                                     SymbolBuildIndex builds, AnrJavaRetrace retrace,
                                     AnrSymbolicationWakeup wakeup, JdbcTemplate jdbc) {
        this.events = events;
        this.states = states;
        this.publications = publications;
        this.symbols = symbols;
        this.builds = builds;
        this.retrace = retrace;
        this.wakeup = wakeup;
        this.jdbc = jdbc;
    }

    /** 先重放未确认结果，再处理一次唤醒并轮转一个活动版本。 */
    public void runOnce() {
        if (retryAfterNanos != 0 && System.nanoTime() - retryAfterNanos < 0) return;
        try {
            for (var pending : publications.pending(10)) publications.publish(pending);
            var recent = wakeup.poll();
            if (recent != null) process(recent.appId(), recent.eventId());
            scanNextBuild();
            retryAfterNanos = 0;
        } catch (RuntimeException failure) {
            retryAfterNanos = System.nanoTime() + RETRY.toNanos();
            LOG.warn("ANR 后台还原暂未完成，类型={}", failure.getClass().getSimpleName());
        }
    }

    /** 恢复并处理同一事件中尚未固定的两个独立分区。 */
    void process(UUID appId, String eventId) {
        Optional<AnrEvent> loaded = events.find(appId, eventId);
        if (loaded.isEmpty()) return;
        AnrEvent event = loaded.get();
        String main = event.payload().mainThreadStack();
        String trace = event.payload().trace() == null ? null : event.payload().trace().text();
        Instant now = Instant.now();
        boolean mainReady = needsWork(main, states.find(appId, eventId, "main"), now);
        boolean traceReady = needsWork(trace, states.find(appId, eventId, "trace"), now);
        if (!mainReady && !traceReady) return;
        Optional<SymbolFileLease> selected;
        try {
            selected = symbols.acquireVersioned(appId, event.metadata().appVersion(), event.metadata().buildId());
        } catch (RuntimeException failure) {
            temporary(event, mainReady, traceReady, "failed", "mapping_unavailable", now);
            LOG.warn("ANR mapping 读取失败，等待重试");
            return;
        }
        if (selected.isEmpty()) {
            temporary(event, mainReady, traceReady, "pending", "mapping_missing", now);
            return;
        }
        try (SymbolFileLease mapping = selected.get()) {
            boolean truncated = event.payload().trace() != null && Boolean.TRUE.equals(event.payload().trace().truncated());
            var result = retrace.resolve(mainReady ? main : null, traceReady ? trace : null, truncated, mapping);
            if (mainReady) save(event, "main", result.mainThread(), mapping, now);
            if (traceReady) save(event, "trace", result.trace(), mapping, now);
        } catch (IOException failure) {
            LOG.warn("ANR mapping 租约释放失败，等待下次对账");
        }
    }

    /** 按已固定状态与重试时间判断是否需要再次处理。 */
    private boolean needsWork(String text, Optional<AnrJavaView> view, Instant now) {
        if (text == null || text.isBlank()) return false;
        if (view.isEmpty()) return true;
        AnrJavaView current = view.get();
        if (List.of("symbolicated", "partial", "not_applicable").contains(current.status())) return false;
        return current.retryAt() == null || !current.retryAt().isAfter(now);
    }

    /** 为临时失败或缺少 mapping 的分区单独记录安全状态。 */
    private void temporary(AnrEvent event, boolean main, boolean trace, String status, String reason, Instant now) {
        var view = new AnrJavaView(status, reason, null, null, null, null, now, now.plus(RETRY));
        if (main) states.write(event.metadata().appId(), event.metadata().eventId(), event.metadata().occurredAt(),
                "main", now.toEpochMilli(), view);
        if (trace) states.write(event.metadata().appId(), event.metadata().eventId(), event.metadata().occurredAt(),
                "trace", now.toEpochMilli(), view);
    }

    /** 成功结果先固定在管理库，失败结果按有限间隔重试。 */
    private void save(AnrEvent event, String section, AnrJavaRetrace.Section result, SymbolFileLease mapping, Instant now) {
        if ("failed".equals(result.status())) {
            var view = new AnrJavaView("failed", result.reason(), null, null, null, null, now, now.plus(RETRY));
            states.write(event.metadata().appId(), event.metadata().eventId(), event.metadata().occurredAt(),
                    section, now.toEpochMilli(), view);
            return;
        }
        boolean hasResult = result.text() != null;
        var view = new AnrJavaView(result.status(), result.reason(), result.text(), hasResult ? mapping.symbolId() : null,
                hasResult ? mapping.revision() : null, hasResult ? mapping.sha256() : null, now, null);
        publications.register(event.metadata().appId(), event.metadata().eventId(), event.metadata().occurredAt(), section, view)
                .ifPresent(publications::publish);
    }

    /** 每次只扫描一个活动 mapping 版本的一小页，并持久化页尾。 */
    private void scanNextBuild() {
        List<SymbolBuildIndex.Build> next = builds.activeAfter(afterSymbol, 1);
        if (next.isEmpty()) {
            afterSymbol = null;
            return;
        }
        var build = next.getFirst();
        if (!"MAPPING".equals(build.symbolType())) {
            afterSymbol = build.symbolId();
            return;
        }
        List<String> positions = jdbc.query("SELECT after_event_id FROM anr_symbolication_cursor WHERE app_id=? AND app_version=?",
                (row, number) -> row.getString(1), build.appId(), build.appVersion());
        String position = positions.isEmpty() ? null : positions.getFirst();
        List<String> ids = events.candidateIds(build.appId(), build.appVersion(), Instant.now().minus(Duration.ofDays(30)),
                position, PAGE_SIZE);
        for (String id : ids) process(build.appId(), id);
        String last = ids.size() < PAGE_SIZE ? null : ids.getLast();
        jdbc.update("""
                INSERT INTO anr_symbolication_cursor(app_id,app_version,after_event_id,updated_at)
                VALUES (?,?,?,clock_timestamp()) ON CONFLICT (app_id,app_version)
                DO UPDATE SET after_event_id=EXCLUDED.after_event_id,updated_at=EXCLUDED.updated_at
                """, build.appId(), build.appVersion(), last);
        afterSymbol = build.symbolId();
        LOG.debug("ANR 版本对账完成，候选数={}", ids.size());
    }
}
