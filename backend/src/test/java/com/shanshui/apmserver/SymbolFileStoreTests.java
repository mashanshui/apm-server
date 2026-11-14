package com.shanshui.apmserver;

import com.shanshui.apmserver.symbol.api.SymbolStoreUnavailableException;
import com.shanshui.apmserver.symbol.api.SymbolValidationException;
import com.shanshui.apmserver.symbol.internal.config.SymbolProperties;
import com.shanshui.apmserver.symbol.internal.domain.SymbolFileEntity;
import com.shanshui.apmserver.symbol.internal.storage.SymbolFileStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 覆盖符号表受控文件卷的大小、原子发布、租约和孤立文件清理。 */
class SymbolFileStoreTests {

    @TempDir
    Path root;

    /** 暂存文件计算摘要，超过上限时删除临时文件。 */
    @Test
    void stagesAndRejectsOversizedFiles() throws Exception {
        SymbolFileStore store = store(8);
        SymbolFileStore.StagedFile staged = store.stage(
                new ByteArrayInputStream("com.example.App -> a:".getBytes()), 64);
        assertEquals(21, staged.sizeBytes());
        assertTrue(Files.exists(staged.path()));
        store.discard(staged);
        assertFalse(Files.exists(staged.path()));

        assertThrows(SymbolValidationException.class,
                () -> store.stage(new ByteArrayInputStream("0123456789".getBytes()), 4));
    }

    /** 旧版本在租约释放前必须保留，最后一个租约释放后才回收。 */
    @Test
    void retainsRetiredFileUntilLeaseCloses() throws Exception {
        SymbolFileStore store = store(128);
        SymbolFileStore.StagedFile staged = store.stage(
                new ByteArrayInputStream("com.example.App -> a:".getBytes()), 128);
        String key = store.publish(staged);
        UUID symbolId = UUID.randomUUID();
        SymbolFileEntity entity = new SymbolFileEntity(symbolId, UUID.randomUUID(), "release-1", 1,
                key, "mapping.txt", 21, staged.sha256(), UUID.randomUUID(), Instant.now(), Instant.now());

        var lease = store.open(entity);
        var retained = lease.retain();
        store.retire(key);
        assertTrue(Files.exists(lease.path()));
        lease.close();
        assertTrue(Files.exists(retained.path()));
        assertEquals(symbolId, retained.symbolId());
        assertEquals(staged.sha256(), retained.sha256());
        assertThrows(IllegalStateException.class, lease::retain);
        retained.close();
        assertFalse(Files.exists(lease.path()));
    }

    /** 末个旧租约释放与新读取并发时，新租约仍能阻止退休回收。 */
    @Test
    void retainsFileWhenOpeningAndClosingConcurrently() throws Exception {
        /** 反复交错登记和释放，覆盖同一存储键的计数转换。 */
        SymbolFileStore store = store(128);
        try (var workers = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            for (int index = 0; index < 500; index++) {
                var staged = store.stage(new ByteArrayInputStream("symbols".getBytes()), 128);
                var key = store.publish(staged);
                var entity = new SymbolFileEntity(UUID.randomUUID(), UUID.randomUUID(), "build", 1, key,
                        "symbols.so", staged.sizeBytes(), staged.sha256(), UUID.randomUUID(), Instant.now(), Instant.now());
                var old = store.open(entity);
                var gate = new java.util.concurrent.CyclicBarrier(2);
                var closer = workers.submit(() -> { gate.await(); old.close(); return null; });
                var opener = workers.submit(() -> { gate.await(); return store.open(entity); });
                var current = opener.get();
                closer.get();
                store.retire(key);
                assertEquals("symbols", Files.readString(current.path()));
                current.close();
                assertFalse(Files.exists(current.path()));
            }
        }
    }

    /** 启动清理只能删除数据库未引用的 mapping 文件和遗留暂存文件。 */
    @Test
    void collectsOnlyUnreferencedMappingFiles() throws Exception {
        SymbolFileStore store = store(128);
        Path referenced = store.root().resolve(UUID.randomUUID() + ".mapping");
        Path orphan = store.root().resolve(UUID.randomUUID() + ".mapping");
        Path temporary = store.root().resolve(".upload-stale.mapping");
        Files.writeString(referenced, "referenced");
        Files.writeString(orphan, "orphan");
        Files.writeString(temporary, "temporary");

        store.collectOrphans(Set.of(referenced.getFileName().toString()));

        assertTrue(Files.exists(referenced));
        assertFalse(Files.exists(orphan));
        assertFalse(Files.exists(temporary));
    }

    /** 读取中的旧文件即使启动清理发现未引用，也必须等租约释放后再清理。 */
    @Test
    void keepsUnreferencedFileWhileLeaseIsActive() throws Exception {
        SymbolFileStore store = store(128);
        SymbolFileStore.StagedFile staged = store.stage(
                new ByteArrayInputStream("com.example.App -> a:".getBytes()), 128);
        String key = store.publish(staged);
        SymbolFileEntity entity = new SymbolFileEntity(UUID.randomUUID(), UUID.randomUUID(), "lease-build", 1,
                key, "mapping.txt", staged.sizeBytes(), staged.sha256(), UUID.randomUUID(), Instant.now(), Instant.now());

        var lease = store.open(entity);
        store.collectOrphans(Set.of());
        assertTrue(Files.exists(lease.path()));
        lease.close();
        store.collectOrphans(Set.of());
        assertFalse(Files.exists(lease.path()));
    }

    /** 文件存储实例重建后仍能从同一持久目录打开数据库引用的 mapping。 */
    @Test
    void reopensPublishedFileAfterStoreInstanceRestart() throws Exception {
        SymbolFileStore firstProcessStore = store(128);
        byte[] mappingContent = "com.example.App -> a:".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        SymbolFileStore.StagedFile staged = firstProcessStore.stage(new ByteArrayInputStream(mappingContent), 128);
        String storageKey = firstProcessStore.publish(staged);
        SymbolFileEntity entity = new SymbolFileEntity(UUID.randomUUID(), UUID.randomUUID(), "restart-build", 1,
                storageKey, "mapping.txt", staged.sizeBytes(), staged.sha256(), UUID.randomUUID(),
                Instant.now(), Instant.now());

        // 新实例模拟应用进程重启；共享的受控目录模拟仍挂载的持久文件卷。
        SymbolFileStore restartedProcessStore = store(128);
        var lease = restartedProcessStore.open(entity);
        try {
            assertEquals("com.example.App -> a:", Files.readString(lease.path()));
            assertEquals(1, lease.revision());
        } finally {
            lease.close();
        }
    }

    /** 受控键和文件读取失败必须返回安全的存储错误，不允许访问目录外路径。 */
    @Test
    void rejectsUnsafeOrMissingPublishedPath() {
        SymbolFileStore store = store(128);
        SymbolFileEntity unsafe = new SymbolFileEntity(UUID.randomUUID(), UUID.randomUUID(), "build", 1,
                "../outside.mapping", "mapping.txt", 1, "a".repeat(64), UUID.randomUUID(),
                Instant.now(), Instant.now());
        assertThrows(SymbolStoreUnavailableException.class, () -> store.open(unsafe));
    }

    /** 输入流读取失败时必须删除暂存文件并转换为可重试存储错误。 */
    @Test
    void cleansTemporaryFileWhenInputReadFails() {
        SymbolFileStore store = store(128);
        InputStream failing = new InputStream() {
            @Override
            public int read() throws IOException {
                throw new IOException("simulated read failure");
            }
        };
        assertThrows(SymbolStoreUnavailableException.class, () -> store.stage(failing, 128));
        assertTrue(() -> {
            try (var files = Files.list(root)) {
                return files.findAny().isEmpty();
            } catch (IOException ex) {
                return false;
            }
        });
    }

    /** 构造测试用符号文件存储配置。 */
    private SymbolFileStore store(long maxBytes) {
        SymbolProperties properties = new SymbolProperties();
        properties.setDirectory(root.toString());
        properties.setMaxFileBytes(maxBytes);
        properties.setMaxOutputBytes(1024);
        properties.setMaxConcurrentOperations(1);
        return new SymbolFileStore(properties);
    }
}
