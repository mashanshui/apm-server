package com.shanshui.apmserver.symbol.internal.storage;

import com.shanshui.apmserver.symbol.api.SymbolFileLease;
import com.shanshui.apmserver.symbol.api.SymbolStoreUnavailableException;
import com.shanshui.apmserver.symbol.api.SymbolValidationException;
import com.shanshui.apmserver.symbol.internal.config.SymbolProperties;
import com.shanshui.apmserver.symbol.internal.domain.SymbolFileEntity;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/** 受控符号文件卷，负责暂存、原子发布和读取租约。 */
@Component
public class SymbolFileStore {

    /** 受控目录下暂存文件的前缀。 */
    private static final String TEMP_PREFIX = ".upload-";
    /** 符号文件扩展名。 */
    private static final String SYMBOL_SUFFIX = ".mapping";

    /** 规范化后的受控根目录。 */
    private final Path root;
    /** 每个文件当前持有的读取租约数。 */
    private final Map<String, AtomicInteger> leases = new ConcurrentHashMap<>();
    /** 已切换但仍等待读取者退出的旧文件。 */
    private final Set<String> retired = ConcurrentHashMap.newKeySet();

    /** 初始化并创建受控目录。 */
    public SymbolFileStore(SymbolProperties properties) {
        if (properties.getDirectory() == null || properties.getDirectory().isBlank()) {
            throw new IllegalStateException("apm.symbol.directory 不能为空");
        }
        if (properties.getMaxFileBytes() <= 0 || properties.getMaxOutputBytes() <= 0
                || properties.getMaxConcurrentOperations() <= 0) {
            throw new IllegalStateException("符号表资源上限必须为正数");
        }
        try {
            this.root = Path.of(properties.getDirectory()).toAbsolutePath().normalize();
            Files.createDirectories(root);
        } catch (IOException | RuntimeException ex) {
            throw new IllegalStateException("符号表目录无法创建", ex);
        }
    }

    /** 将上传流写入同卷暂存文件并计算摘要。 */
    public StagedFile stage(InputStream input, long maxBytes) {
        if (input == null || maxBytes <= 0) {
            throw new SymbolValidationException("INVALID_MAPPING", "mapping 输入或大小上限无效", 422);
        }
        Path temporary = null;
        try {
            temporary = Files.createTempFile(root, TEMP_PREFIX, SYMBOL_SUFFIX);
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            long size = 0;
            byte[] buffer = new byte[8192];
            try (InputStream source = input; var output = Files.newOutputStream(temporary)) {
                for (int count = source.read(buffer); count >= 0; count = source.read(buffer)) {
                    if (count == 0) {
                        continue;
                    }
                    size += count;
                    if (size > maxBytes) {
                        throw new SymbolValidationException("MAPPING_TOO_LARGE", "mapping 文件超过大小上限", 413);
                    }
                    digest.update(buffer, 0, count);
                    output.write(buffer, 0, count);
                }
            }
            if (size == 0) {
                throw new SymbolValidationException("INVALID_MAPPING", "mapping 文件不能为空", 422);
            }
            return new StagedFile(temporary, size, HexFormat.of().formatHex(digest.digest()));
        } catch (SymbolValidationException | SymbolStoreUnavailableException ex) {
            deleteQuietly(temporary);
            throw ex;
        } catch (IOException | NoSuchAlgorithmException ex) {
            deleteQuietly(temporary);
            throw new SymbolStoreUnavailableException(ex);
        }
    }

    /** 将已校验暂存文件以不可变服务端键原子发布。 */
    public String publish(StagedFile staged) {
        String storageKey = UUID.randomUUID() + SYMBOL_SUFFIX;
        Path target = safePath(storageKey);
        try {
            moveAtomically(staged.path(), target);
            return storageKey;
        } catch (IOException ex) {
            deleteQuietly(staged.path());
            throw new SymbolStoreUnavailableException(ex);
        }
    }

    /** 删除尚未发布的暂存文件。 */
    public void discard(StagedFile staged) {
        if (staged != null) {
            deleteQuietly(staged.path());
        }
    }

    /** 取得数据库当前版本对应文件的读取租约。 */
    public SymbolFileLease open(SymbolFileEntity entity) {
        Path path = safePath(entity.getStorageKey());
        leases.computeIfAbsent(entity.getStorageKey(), ignored -> new AtomicInteger()).incrementAndGet();
        try {
            Path realRoot = root.toRealPath();
            Path realPath = path.toRealPath();
            if (!realPath.startsWith(realRoot) || !Files.isRegularFile(realPath) || !Files.isReadable(realPath)) {
                throw new IOException("symbol file is not readable");
            }
            return new Lease(entity.getSymbolId(), entity.getRevision(), entity.getStorageKey(), realPath, entity.getSha256());
        } catch (IOException ex) {
            release(entity.getStorageKey());
            throw new SymbolStoreUnavailableException(ex);
        }
    }

    /** 标记旧文件并在没有读取者时立即回收。 */
    public void retire(String storageKey) {
        if (storageKey == null || storageKey.isBlank()) {
            return;
        }
        retired.add(storageKey);
        collect(storageKey);
    }

    /** 回收数据库不再引用且没有读取者的内部文件。 */
    public void collectOrphans(Set<String> referencedKeys) {
        try {
            if (!Files.exists(root)) {
                return;
            }
            try (var stream = Files.list(root)) {
                stream.filter(Files::isRegularFile)
                        .filter(path -> path.getFileName().toString().endsWith(SYMBOL_SUFFIX))
                        .map(path -> path.getFileName().toString())
                        .filter(key -> !referencedKeys.contains(key))
                        .filter(key -> leases.getOrDefault(key, new AtomicInteger()).get() == 0)
                        .forEach(this::deleteOrphanQuietly);
            }
        } catch (IOException ex) {
            throw new SymbolStoreUnavailableException(ex);
        }
    }

    /** 返回文件卷的绝对路径，便于启动检查和测试。 */
    public Path root() {
        return root;
    }

    /** 校验服务端生成的存储键，防止路径逃逸。 */
    private Path safePath(String storageKey) {
        if (storageKey == null || !storageKey.matches("[0-9a-fA-F-]{36}\\.mapping")) {
            throw new SymbolStoreUnavailableException("符号表存储键无效");
        }
        Path path = root.resolve(storageKey).normalize();
        if (!path.startsWith(root)) {
            throw new SymbolStoreUnavailableException("符号表存储路径无效");
        }
        return path;
    }

    /** 在同一文件卷内执行原子移动，不支持时使用不覆盖目标的替代方案。 */
    private void moveAtomically(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException ex) {
            Files.move(source, target);
        }
    }

    /** 释放读取租约，并按需删除已退休文件。 */
    private void release(String storageKey) {
        AtomicInteger count = leases.get(storageKey);
        if (count != null && count.decrementAndGet() <= 0) {
            leases.remove(storageKey, count);
            collect(storageKey);
        }
    }

    /** 在没有租约时回收一个已退休文件。 */
    private void collect(String storageKey) {
        if (!retired.contains(storageKey)) {
            return;
        }
        AtomicInteger count = leases.get(storageKey);
        if (count != null && count.get() > 0) {
            return;
        }
        retired.remove(storageKey);
        deleteKeyQuietly(storageKey);
    }

    /** 安静删除一个服务端存储键。 */
    private void deleteKeyQuietly(String storageKey) {
        try {
            Files.deleteIfExists(safePath(storageKey));
        } catch (IOException | RuntimeException ignored) {
            // 清理失败保留文件，等待下一次启动回收。
        }
    }

    /** 删除启动时发现的内部暂存文件或已发布文件。 */
    private void deleteOrphanQuietly(String storageKey) {
        if (storageKey.startsWith(TEMP_PREFIX)) {
            try {
                Files.deleteIfExists(root.resolve(storageKey).normalize());
            } catch (IOException | RuntimeException ignored) {
                // 暂存文件清理失败留待下一次启动处理。
            }
            return;
        }
        deleteKeyQuietly(storageKey);
    }

    /** 安静删除任意暂存路径。 */
    private void deleteQuietly(Path path) {
        if (path == null) {
            return;
        }
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // 暂存文件清理失败不覆盖原始错误。
        }
    }

    /** 暂存文件及其完整摘要。 */
    public record StagedFile(Path path, long sizeBytes, String sha256) {
    }

    /** 文件读取租约实现。 */
    private final class Lease implements SymbolFileLease {

        /** 符号表记录标识。 */
        private final UUID symbolId;
        /** 读取时固定的版本。 */
        private final int revision;
        /** 服务端存储键。 */
        private final String storageKey;
        /** 受控文件路径。 */
        private final Path path;
        /** 与读取租约相同版本的登记摘要。 */
        private final String sha256;
        /** 防止重复释放。 */
        private boolean closed;

        /** 创建一个已计数的文件读取租约。 */
        private Lease(UUID symbolId, int revision, String storageKey, Path path, String sha256) {
            this.symbolId = symbolId;
            this.revision = revision;
            this.storageKey = storageKey;
            this.path = path;
            this.sha256 = sha256;
        }

        /** 返回符号表记录标识。 */
        @Override
        public UUID symbolId() { return symbolId; }
        /** 返回固定版本。 */
        @Override
        public int revision() { return revision; }
        /** 返回本次固定版本摘要。 */
        @Override
        public String sha256() { return sha256; }
        /** 返回受控文件路径。 */
        @Override
        public Path path() { return path; }

        /** 释放当前文件的读取计数。 */
        @Override
        public synchronized void close() {
            if (!closed) {
                closed = true;
                release(storageKey);
            }
        }
    }
}
