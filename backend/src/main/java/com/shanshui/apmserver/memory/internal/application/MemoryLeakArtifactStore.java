package com.shanshui.apmserver.memory.internal.application;

import com.shanshui.apmserver.memory.api.MemoryLeakReportConfiguration;
import com.shanshui.apmserver.memory.api.MemoryLeakAttachmentStoreException;
import com.shanshui.apmserver.platform.api.LimitedInputStream;
import com.shanshui.apmserver.platform.api.PayloadTooLargeException;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Set;
import java.util.UUID;

/** 附件只写入受限目录；文件名由服务端 UUID 生成，永不使用客户端文件名。 */
@Service
public class MemoryLeakArtifactStore {
    private final MemoryLeakReportConfiguration config;
    public MemoryLeakArtifactStore(MemoryLeakReportConfiguration config) { this.config = config; }

    public StoredArtifact store(UUID appId, UUID eventId, InputStream input) {
        Path root = Path.of(config.getArtifactDirectory()).toAbsolutePath().normalize();
        Path temporary = null;
        try {
            Files.createDirectories(root);
            temporary = Files.createTempFile(root, "upload-", ".tmp");
            long size = 0;
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream limited = new LimitedInputStream(input, config.getMaxAttachmentBytes(), "HPROF 附件超过大小上限");
                 OutputStream output = Files.newOutputStream(temporary)) {
                byte[] buffer = new byte[8192];
                for (int read = limited.read(buffer); read >= 0; read = limited.read(buffer)) {
                    if (read == 0) continue;
                    size += read; digest.update(buffer, 0, read); output.write(buffer, 0, read);
                }
            }
            String hash = HexFormat.of().formatHex(digest.digest());
            Path destination = root.resolve(appId + "-" + eventId + ".hprof").normalize();
            if (!destination.getParent().equals(root)) throw new IOException("附件目录无效");
            try {
                Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (java.nio.file.AtomicMoveNotSupportedException ex) {
                Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING);
            }
            return new StoredArtifact(hash, destination.toString(), size);
        } catch (PayloadTooLargeException ex) {
            deleteTemporary(temporary);
            throw ex;
        } catch (Exception ex) {
            deleteTemporary(temporary);
            throw new MemoryLeakAttachmentStoreException("附件保存失败", ex);
        }
    }

    private void deleteTemporary(Path temporary) {
        if (temporary != null) try { Files.deleteIfExists(temporary); } catch (IOException ignored) { }
    }

    /** 在不覆盖已保存附件的前提下计算重试附件摘要，并沿用上传大小限制。 */
    public String digest(InputStream input) {
        try (InputStream limited = new LimitedInputStream(input, config.getMaxAttachmentBytes(), "HPROF 附件超过大小上限")) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[8192];
            for (int read = limited.read(buffer); read >= 0; read = limited.read(buffer)) {
                if (read > 0) digest.update(buffer, 0, read);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (PayloadTooLargeException ex) { throw ex;
        } catch (Exception ex) { throw new MemoryLeakAttachmentStoreException("附件摘要计算失败", ex); }
    }

    public void delete(String path) {
        if (path == null) return;
        Path root = Path.of(config.getArtifactDirectory()).toAbsolutePath().normalize();
        Path candidate = Path.of(path).toAbsolutePath().normalize();
        if (candidate.startsWith(root)) try { Files.deleteIfExists(candidate); } catch (IOException ignored) { }
    }

    /** 清理临时文件和已经超过保留期的附件，所有操作均限制在配置根目录内。 */
    public CleanupResult cleanup(Set<String> referencedPaths, Instant now) {
        Path root = Path.of(config.getArtifactDirectory()).toAbsolutePath().normalize();
        Set<Path> referenced = referencedPaths == null ? Set.of() : referencedPaths.stream()
                .map(path -> Path.of(path).toAbsolutePath().normalize())
                .filter(path -> path.startsWith(root)).collect(java.util.stream.Collectors.toUnmodifiableSet());
        long orphanCutoff = now.minus(Duration.ofHours(Math.max(0, config.getOrphanGraceHours()))).toEpochMilli();
        long retentionCutoff = now.minus(Duration.ofDays(Math.max(0, config.getAttachmentRetentionDays()))).toEpochMilli();
        int temporaryDeleted = 0;
        int attachmentDeleted = 0;
        try (var files = Files.list(root)) {
            for (Path file : files.filter(Files::isRegularFile).toList()) {
                String name = file.getFileName().toString();
                try {
                    long modified = Files.getLastModifiedTime(file).toMillis();
                    if (name.startsWith("upload-") && name.endsWith(".tmp")) {
                        if (modified <= orphanCutoff && Files.deleteIfExists(file)) temporaryDeleted++;
                    } else if (name.endsWith(".hprof") && modified <= (referenced.contains(file) ? retentionCutoff : orphanCutoff)) {
                        if (Files.deleteIfExists(file)) attachmentDeleted++;
                    }
                } catch (IOException ignored) {
                    // 单个文件失败不影响其余文件，下一轮清理会重试。
                }
            }
        } catch (IOException ignored) {
            // 目录不存在或暂时不可读时由下一轮重试，不影响纯 JSON 上报。
        }
        return new CleanupResult(temporaryDeleted, attachmentDeleted);
    }

    public record StoredArtifact(String digest, String path, long bytes) { }
    public record CleanupResult(int temporaryDeleted, int attachmentDeleted) { }
}
