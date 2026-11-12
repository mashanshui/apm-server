package com.shanshui.apmserver;

import com.shanshui.apmserver.crash.api.CrashSymbolicationWork;
import com.shanshui.apmserver.crash.internal.application.*;
import com.shanshui.apmserver.crash.internal.domain.CrashEvent;
import com.shanshui.apmserver.crash.internal.persistence.InMemoryCrashRepository;
import com.shanshui.apmserver.crash.internal.port.CrashWritePort;
import com.shanshui.apmserver.platform.api.EventStoreUnavailableException;
import com.shanshui.apmserver.symbol.api.*;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 接收确认与后台通知分开：没有确认不能假成功，通知失败不能推翻已确认数据。 */
class CrashIngestionBackgroundTests {
    /** Native 接收和重试都不查询 mapping，也不会唤醒 JVM 后台任务。 */
    @Test
    void nativeIngestionDoesNotConsultJvmSymbolRegistry() {
        var properties = CrashTestSupport.ingestProperties();
        var store = new com.shanshui.apmserver.crash.internal.persistence.InMemoryCrashStateStore(
                CrashTestSupport.storageProperties(), CrashTestSupport.objectMapper());
        var repository = new InMemoryCrashRepository(CrashTestSupport.storageProperties(), store);
        var work = mock(CrashSymbolicationWork.class);
        var symbols = mock(SymbolRegistry.class);
        var processor = new CrashEventProcessor(new CrashEventValidator(properties, CrashTestSupport.objectMapper()),
                new CrashSanitizer(properties), new CrashFingerprintService(), repository, CrashTestSupport.objectMapper(), work, symbols);
        var nativeCrash = new com.shanshui.apmserver.crash.api.NativeCrash("arm64-v8a", 7, null, null,
                new com.shanshui.apmserver.crash.api.NativeCrash.Signal(11, "SIGSEGV", 1, null, "0x0", null),
                new com.shanshui.apmserver.crash.api.NativeCrash.Registers("ok", null, false, null, Map.of("pc", "0x1")),
                List.of(), new com.shanshui.apmserver.crash.api.NativeCrash.Backtrace("unavailable", "io_error", false, null, List.of()), null);
        var payload = new com.shanshui.apmserver.crash.api.CrashPayload("native", true, null, null, nativeCrash);
        var event = CrashTestSupport.crashCommand(CrashTestSupport.event("native", "crash", "session", "device", "3.2.0",
                System.currentTimeMillis(), payload));
        UUID app = UUID.randomUUID();
        assertEquals(1, processor.ingest(app, event, Instant.now()).appendResult().accepted());
        assertEquals(1, processor.ingest(app, event, Instant.now()).appendResult().duplicate());
        verifyNoInteractions(work, symbols);
        assertTrue(store.candidates(app, event.buildId(), UUID.randomUUID(), 1,
                Instant.now().minusSeconds(86400), Instant.now(), 100).isEmpty());
        assertEquals("raw_native", store.current(app, event.eventId()).orElseThrow().stackFormat());
        // 事实或状态无法确认时不能返回 accepted，也不应触发符号工作。
        var unavailable = mock(CrashWritePort.class);
        when(unavailable.append(eq(app), anyList())).thenThrow(new EventStoreUnavailableException());
        var rejected = new CrashEventProcessor(new CrashEventValidator(properties, CrashTestSupport.objectMapper()),
                new CrashSanitizer(properties), new CrashFingerprintService(), unavailable, CrashTestSupport.objectMapper(), work, symbols);
        assertThrows(EventStoreUnavailableException.class, () -> rejected.ingest(app, event, Instant.now()));
        verifyNoInteractions(work, symbols);
    }

    /** 已有 mapping 也只保存 pending；重复原事件与失败通知均不执行 R8。 */
    @Test
    void persistsPendingAndNeverRetracesDuringIngest() throws Exception {
        // 使用真实校验、脱敏与当前内存参考数据集。
        var properties = CrashTestSupport.ingestProperties();
        var repository = new InMemoryCrashRepository(CrashTestSupport.storageProperties());
        var work = mock(CrashSymbolicationWork.class);
        var symbols = mock(SymbolRegistry.class);
        var mapping = mock(SymbolFileLease.class);
        var app = UUID.randomUUID();
        var event = CrashTestSupport.crashCommand(CrashTestSupport.event("ingest", "crash", "session", "device", "3.2.0",
                System.currentTimeMillis(), CrashTestSupport.crash("java.lang.RuntimeException", "test", 1, "a")));
        when(symbols.acquireVersioned(app, event.appVersion(), event.buildId())).thenReturn(Optional.of(mapping));
        var processor = new CrashEventProcessor(new CrashEventValidator(properties, CrashTestSupport.objectMapper()),
                new CrashSanitizer(properties), new CrashFingerprintService(), repository, CrashTestSupport.objectMapper(), work, symbols);
        assertEquals(1, processor.ingest(app, event, Instant.now()).appendResult().accepted());
        var stored = (CrashEvent) repository.findAll(app).getFirst();
        assertEquals("pending", stored.state().status());
        assertEquals("raw_chain", stored.state().stackFormat());
        assertEquals(0, stored.state().stateVersion());
        doThrow(new EventStoreUnavailableException()).when(work).wake(app, event.appVersion());
        assertEquals(1, processor.ingest(app, event, Instant.now()).appendResult().duplicate());
        verify(symbols, never()).retrace(any(), anyList());
        verify(mapping, times(2)).close();
        // 未确认的存储故障必须向调用方传播，且不能先登记后台工作。
        var unavailable = mock(CrashWritePort.class);
        when(unavailable.append(eq(app), anyList())).thenThrow(new EventStoreUnavailableException());
        reset(work, symbols);
        var rejected = new CrashEventProcessor(new CrashEventValidator(properties, CrashTestSupport.objectMapper()),
                new CrashSanitizer(properties), new CrashFingerprintService(), unavailable, CrashTestSupport.objectMapper(), work, symbols);
        assertThrows(EventStoreUnavailableException.class, () -> rejected.ingest(app, event, Instant.now()));
        verifyNoInteractions(work, symbols);
    }
}
