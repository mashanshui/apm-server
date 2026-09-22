package com.shanshui.apmserver;

import com.shanshui.apmserver.bootstrap.internal.config.IngestConfigurationProperties;
import com.shanshui.apmserver.crash.api.CrashEventDetailResponse;
import com.shanshui.apmserver.crash.internal.application.CrashQueryService;
import com.shanshui.apmserver.crash.internal.persistence.InMemoryCrashRepository;
import com.shanshui.apmserver.platform.api.StorageProperties;
import com.shanshui.apmserver.symbol.api.SymbolFileLease;
import com.shanshui.apmserver.symbol.api.SymbolRegistry;
import com.shanshui.apmserver.symbol.api.SymbolicationResult;
import com.shanshui.apmserver.symbol.api.SymbolStoreUnavailableException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/** 验证 Crash 详情每次请求实时使用当前 mapping，且不修改事件存储。 */
class CrashLiveSymbolicationTests {

    /** 固定测试应用。 */
    private static final UUID APP_ID = TestAppIds.id("live-symbol-app");
    /** 内存 Crash 仓库。 */
    private InMemoryCrashRepository repository;
    /** 记录每次注册表调用的伪注册表。 */
    private CountingSymbolRegistry registry;
    /** Crash 查询服务。 */
    private CrashQueryService query;

    /** 初始化一个带伪符号注册表的 Crash 查询服务。 */
    @BeforeEach
    void setUp() {
        IngestConfigurationProperties properties = CrashTestSupport.ingestProperties();
        repository = new InMemoryCrashRepository(new StorageProperties());
        var ingestion = CrashTestSupport.ingestion(repository, properties);
        ingestion.ingest(APP_ID, CrashTestSupport.batch(java.util.List.of(
                CrashTestSupport.event("live-symbol-event", "crash", "live-session", "live-device",
                        "3.2.0", CrashTestSupport.nowMillis(),
                        CrashTestSupport.crash("a.b.ObfuscatedException", "boom", 12, "a.b.C")))));
        registry = new CountingSymbolRegistry();
        query = new CrashQueryService(repository, CrashTestSupport.queryProperties(),
                CrashTestSupport.storageProperties(),
                new com.shanshui.apmserver.bootstrap.internal.observability.MicrometerTelemetryMetrics(
                        new SimpleMeterRegistry()), registry);
    }

    /** 连续详情请求必须各自执行还原并保持原始事件、指纹和统计不变。 */
    @Test
    void retracesEveryDetailRequestWithoutWritingDerivedText() {
        int before = repository.findAll(APP_ID).size();
        CrashEventDetailResponse first = query.event(APP_ID, "live-symbol-event");
        CrashEventDetailResponse second = query.event(APP_ID, "live-symbol-event");

        assertEquals(2, registry.retraceCalls);
        assertEquals(2, registry.closedLeases);
        assertEquals(before, repository.findAll(APP_ID).size());
        assertEquals("symbolicated", first.symbolicationStatus());
        assertEquals("symbolicated stack", first.symbolicatedStackText());
        assertEquals(first.fingerprint(), second.fingerprint());
        assertNotNull(second.rawCrash());
    }

    /** 注册表或文件卷不可用时仍返回原始详情，并标记本次请求的失败原因。 */
    @Test
    void keepsRawDetailWhenMappingStoreIsUnavailable() {
        SymbolRegistry unavailable = new SymbolRegistry() {
            @Override
            public Optional<SymbolFileLease> acquire(UUID appId, String buildId) {
                throw new SymbolStoreUnavailableException("registry unavailable");
            }

            @Override
            public SymbolicationResult retrace(SymbolFileLease lease, java.util.List<String> stackLines) {
                throw new AssertionError("存储不可用时不应执行 Retrace");
            }
        };
        CrashQueryService unavailableQuery = new CrashQueryService(repository, CrashTestSupport.queryProperties(),
                CrashTestSupport.storageProperties(),
                new com.shanshui.apmserver.bootstrap.internal.observability.MicrometerTelemetryMetrics(
                        new SimpleMeterRegistry()), unavailable);

        CrashEventDetailResponse response = unavailableQuery.event(APP_ID, "live-symbol-event");

        assertEquals("failed", response.symbolicationStatus());
        assertEquals("mapping_unavailable", response.symbolicationReason());
        assertNotNull(response.rawCrash());
        assertEquals(null, response.symbolicatedStackText());
    }

    /** mapping 替换后新请求必须取得新租约版本，旧响应不写回事件。 */
    @Test
    void usesCurrentLeaseVersionAfterReplacement() {
        SwitchingSymbolRegistry switching = new SwitchingSymbolRegistry();
        CrashQueryService switchingQuery = new CrashQueryService(repository, CrashTestSupport.queryProperties(),
                CrashTestSupport.storageProperties(),
                new com.shanshui.apmserver.bootstrap.internal.observability.MicrometerTelemetryMetrics(
                        new SimpleMeterRegistry()), switching);

        CrashEventDetailResponse before = switchingQuery.event(APP_ID, "live-symbol-event");
        switching.revision = 8;
        CrashEventDetailResponse after = switchingQuery.event(APP_ID, "live-symbol-event");

        assertEquals(7, before.symbolFileRevision());
        assertEquals(8, after.symbolFileRevision());
        assertEquals("version-7", before.symbolicatedStackText());
        assertEquals("version-8", after.symbolicatedStackText());
        assertEquals(2, switching.closedLeases);
    }

    /** 伪造的当前注册表，只验证查询服务的请求内调用和租约释放。 */
    private static final class CountingSymbolRegistry implements SymbolRegistry {

        /** Retrace 调用次数。 */
        private int retraceCalls;
        /** 关闭的租约次数。 */
        private int closedLeases;

        /** 为所有 buildId 返回固定的伪版本租约。 */
        @Override
        public Optional<SymbolFileLease> acquire(UUID appId, String buildId) {
            return Optional.of(new SymbolFileLease() {
                private boolean closed;

                @Override
                public UUID symbolId() {
                    return UUID.nameUUIDFromBytes("live-symbol".getBytes());
                }

                @Override
                public int revision() {
                    return 7;
                }

                @Override
                public Path path() {
                    return Path.of("live-symbol.mapping");
                }

                @Override
                public void close() throws IOException {
                    if (!closed) {
                        closed = true;
                        closedLeases++;
                    }
                }
            });
        }

        /** 返回固定文本并记录请求次数。 */
        @Override
        public SymbolicationResult retrace(SymbolFileLease lease, java.util.List<String> stackLines) {
            retraceCalls++;
            return new SymbolicationResult("symbolicated stack",
                    SymbolicationResult.Status.SYMBOLICATED, null);
        }
    }

    /** 可切换版本的测试注册表，模拟管理员替换后的新请求。 */
    private static final class SwitchingSymbolRegistry implements SymbolRegistry {

        /** 当前生效的 mapping 版本。 */
        private int revision = 7;
        /** 已释放的租约数量。 */
        private int closedLeases;

        /** 按当前版本返回一个独立租约。 */
        @Override
        public Optional<SymbolFileLease> acquire(UUID appId, String buildId) {
            int leasedRevision = revision;
            return Optional.of(new SymbolFileLease() {
                private boolean closed;

                @Override
                public UUID symbolId() {
                    return UUID.nameUUIDFromBytes(("symbol-" + leasedRevision).getBytes());
                }

                @Override
                public int revision() {
                    return leasedRevision;
                }

                @Override
                public Path path() {
                    return Path.of("symbol-" + leasedRevision + ".mapping");
                }

                @Override
                public void close() {
                    if (!closed) {
                        closed = true;
                        closedLeases++;
                    }
                }
            });
        }

        /** 返回当前租约版本的派生文本。 */
        @Override
        public SymbolicationResult retrace(SymbolFileLease lease, java.util.List<String> stackLines) {
            return new SymbolicationResult("version-" + lease.revision(),
                    SymbolicationResult.Status.SYMBOLICATED, null);
        }
    }
}
