package com.shanshui.apmserver;

import com.shanshui.apmserver.symbol.api.SymbolStoreUnavailableException;
import com.shanshui.apmserver.symbol.internal.application.SymbolManagementService;
import com.shanshui.apmserver.symbol.internal.application.SymbolOperationLimiter;
import com.shanshui.apmserver.symbol.internal.application.SymbolRecordWriter;
import com.shanshui.apmserver.symbol.internal.config.SymbolProperties;
import com.shanshui.apmserver.symbol.internal.domain.SymbolFileEntity;
import com.shanshui.apmserver.symbol.internal.persistence.SymbolFileRepository;
import com.shanshui.apmserver.symbol.internal.retrace.R8RetraceEngine;
import com.shanshui.apmserver.symbol.internal.storage.SymbolFileStore;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.mock.web.MockMultipartFile;

import java.io.InputStream;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/** 验证文件发布或数据库事务失败时不会破坏当前 mapping。 */
class SymbolManagementServiceTests {

    /** 磁盘发布失败必须清理已经校验的暂存文件。 */
    @Test
    void discardsStagedFileWhenPublishFails() {
        SymbolProperties properties = properties();
        SymbolFileRepository repository = mock(SymbolFileRepository.class);
        SymbolRecordWriter writer = mock(SymbolRecordWriter.class);
        SymbolFileStore store = mock(SymbolFileStore.class);
        R8RetraceEngine retrace = mock(R8RetraceEngine.class);
        SymbolFileStore.StagedFile staged = staged("disk-staged");
        when(store.stage(any(InputStream.class), eq(properties.getMaxFileBytes()))).thenReturn(staged);
        doNothing().when(retrace).validate(staged.path());
        when(repository.findByAppIdAndBuildId(any(), eq("release-1"))).thenReturn(Optional.empty());
        when(store.publish(staged)).thenThrow(new SymbolStoreUnavailableException("disk unavailable"));
        SymbolManagementService service = service(repository, writer, store, retrace, properties);

        assertThrows(SymbolStoreUnavailableException.class,
                () -> service.upload(UUID.randomUUID(), UUID.randomUUID(), "release-1",
                        new MockMultipartFile("file", "mapping.txt", "text/plain", "mapping".getBytes())));

        verify(store).discard(staged);
        verifyNoMoreInteractions(writer);
    }

    /** 数据库查询失败时不得发布新文件或删除当前版本。 */
    @Test
    void keepsCurrentFileWhenDatabaseIsUnavailable() {
        SymbolProperties properties = properties();
        SymbolFileRepository repository = mock(SymbolFileRepository.class);
        SymbolRecordWriter writer = mock(SymbolRecordWriter.class);
        SymbolFileStore store = mock(SymbolFileStore.class);
        R8RetraceEngine retrace = mock(R8RetraceEngine.class);
        SymbolFileStore.StagedFile staged = staged("database-staged");
        when(store.stage(any(InputStream.class), eq(properties.getMaxFileBytes()))).thenReturn(staged);
        when(repository.findByAppIdAndBuildId(any(), eq("release-2")))
                .thenThrow(new DataAccessResourceFailureException("database unavailable"));
        SymbolManagementService service = service(repository, writer, store, retrace, properties);

        assertThrows(SymbolStoreUnavailableException.class,
                () -> service.upload(UUID.randomUUID(), UUID.randomUUID(), "release-2",
                        new MockMultipartFile("file", "mapping.txt", "text/plain", "mapping".getBytes())));

        verify(store).discard(staged);
        verifyNoMoreInteractions(writer);
    }

    /** 替换事务失败时只回收新发布文件，保留数据库仍引用的旧文件。 */
    @Test
    void retiresNewFileButNeverCurrentFileWhenReplacementFails() {
        SymbolProperties properties = properties();
        SymbolFileRepository repository = mock(SymbolFileRepository.class);
        SymbolRecordWriter writer = mock(SymbolRecordWriter.class);
        SymbolFileStore store = mock(SymbolFileStore.class);
        R8RetraceEngine retrace = mock(R8RetraceEngine.class);
        UUID appId = UUID.randomUUID();
        UUID symbolId = UUID.randomUUID();
        String oldKey = UUID.randomUUID() + ".mapping";
        String newKey = UUID.randomUUID() + ".mapping";
        SymbolFileEntity current = new SymbolFileEntity(symbolId, appId, "release-3", 1, oldKey,
                "old.txt", 10, "a".repeat(64), UUID.randomUUID(), Instant.now(), Instant.now());
        SymbolFileStore.StagedFile staged = staged("replacement-staged");
        when(store.stage(any(InputStream.class), eq(properties.getMaxFileBytes()))).thenReturn(staged);
        when(repository.findById(symbolId)).thenReturn(Optional.of(current));
        when(store.publish(staged)).thenReturn(newKey);
        when(writer.replace(eq(appId), eq(symbolId), any(), eq(1), eq("new.txt"), eq(staged), eq(newKey)))
                .thenThrow(new DataAccessResourceFailureException("database unavailable"));
        SymbolManagementService service = service(repository, writer, store, retrace, properties);

        assertThrows(SymbolStoreUnavailableException.class,
                () -> service.replace(appId, symbolId, UUID.randomUUID(), 1,
                        new MockMultipartFile("file", "new.txt", "text/plain", "mapping".getBytes())));

        verify(store).retire(newKey);
        verify(store, never()).retire(oldKey);
    }

    /** 构造服务测试使用的配置。 */
    private SymbolProperties properties() {
        SymbolProperties properties = new SymbolProperties();
        properties.setMaxFileBytes(1024);
        properties.setMaxOutputBytes(1024);
        properties.setMaxConcurrentOperations(1);
        properties.setDirectory("build/test-symbols");
        return properties;
    }

    /** 构造服务并注入可替换的文件、数据库和 Retrace 依赖。 */
    private SymbolManagementService service(SymbolFileRepository repository, SymbolRecordWriter writer,
                                            SymbolFileStore store, R8RetraceEngine retrace,
                                            SymbolProperties properties) {
        return new SymbolManagementService(repository, writer, new SymbolOperationLimiter(properties),
                store, retrace, properties);
    }

    /** 创建一个只用于事务失败测试的暂存文件描述。 */
    private SymbolFileStore.StagedFile staged(String name) {
        return new SymbolFileStore.StagedFile(Path.of("build", name + ".mapping"), 7, "b".repeat(64));
    }
}
