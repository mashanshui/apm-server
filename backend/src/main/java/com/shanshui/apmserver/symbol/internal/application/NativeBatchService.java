package com.shanshui.apmserver.symbol.internal.application;

import com.shanshui.apmserver.symbol.api.*;
import com.shanshui.apmserver.symbol.internal.archive.*;
import com.shanshui.apmserver.symbol.internal.elf.*;
import com.shanshui.apmserver.symbol.internal.storage.SymbolFileStore;
import com.shanshui.apmserver.symbol.internal.persistence.SymbolFileRepository;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import java.io.*;
import java.nio.file.Files;
import java.util.*;

/** 有界 ZIP 与 ELF 校验全部完成后才发布，提交未知时保留可能已引用文件。 */
@Service
public class NativeBatchService {
    /** 阶段日志仅含数量和动作。 */
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(NativeBatchService.class);
    /** 受控同卷暂存和不可变文件发布。 */
    private final SymbolFileStore store;
    /** 批次元数据短事务。 */
    private final NativeBatchWriter writer;
    /** 成功提交后逐项检查未引用文件。 */
    private final SymbolFileRepository files;
    /** 注入现有文件卷和管理事务。 */
    public NativeBatchService(SymbolFileStore store, NativeBatchWriter writer, SymbolFileRepository files) {
        this.store = store; this.writer = writer; this.files = files;
    }
    /** 文件内容决定模块身份，客户端目标仅用于核对。 */
    public NativeBatch.Result upload(UUID app, UUID user, String version, MultipartFile file,
                                     String targetAbi, String targetElfId, List<NativeBatch.Expected> expected, long deadline) {
        SymbolManagementService.validateIdentity(version, "appVersion", 128);
        if ((targetAbi == null) != (targetElfId == null)) throw new SymbolValidationException("SO_TARGET_MISMATCH", "目标 ABI 和 ELF Build ID 必须成对提供", 400);
        if (file == null || file.isEmpty()) throw new SymbolValidationException("INVALID_SYMBOL_ARCHIVE", "ZIP 不能为空", 422);
        /** 发布键在确认回滚时回收，提交未知时保留。 */
        var published = new ArrayList<String>();
        SymbolFileStore.StagedFile zip = null;
        /** 提交后的暂存清理失败不得回收已被管理库引用的文件。 */
        boolean metadataCommitted = false;
        try {
            zip = file instanceof com.shanshui.apmserver.symbol.internal.web.NativeMultipartBody.DiskPart disk
                    ? disk.stagedFile() : store.stage(new DeadlineInput(file.getInputStream(), deadline), 256L * 1024 * 1024);
            try (var archive = new NativeArchiveReader().read(zip.path(), store.root(), deadline)) {
                /** 相同模块和摘要合并；不同摘要拒绝整包。 */
                var unique = new TreeMap<String, NativeBatch.Candidate>();
                try {
                for (var entry : archive.entries()) {
                    var elf = new ElfInspector().inspect(entry.path(), deadline);
                    /** 实际文件已受归档预算校验，摘要仍在有期限流中计算。 */
                    var digest = java.security.MessageDigest.getInstance("SHA-256");
                    try (var input = new java.security.DigestInputStream(new DeadlineInput(Files.newInputStream(entry.path()), deadline), digest)) {
                        input.transferTo(OutputStream.nullOutputStream());
                    }
                    var staged = new SymbolFileStore.StagedFile(entry.path(), Files.size(entry.path()), java.util.HexFormat.of().formatHex(digest.digest()));
                    var key = elf.abi() + "/" + elf.elfBuildId();
                    var previous = unique.get(key);
                    if (previous != null) {
                        store.discard(staged);
                        if (!previous.staged().sha256().equals(staged.sha256())) throw new SymbolValidationException("SO_ARCHIVE_IDENTITY_CONFLICT", "归档内同一模块存在不同内容", 422);
                    } else {
                        unique.put(key, new NativeBatch.Candidate(safeName(entry.name()), elf, staged, null));
                        if (unique.size() > 128) throw new SymbolValidationException("INVALID_SYMBOL_ARCHIVE", "SO 模块身份超过上限", 422);
                    }
                }
                    if (targetAbi != null && !unique.containsKey(targetAbi + "/" + targetElfId)) {
                        throw new SymbolValidationException("SO_TARGET_MISMATCH", "归档不包含指定模块", 422);
                    }
                    /** 所有材料校验后才发布不可变文件，随后统一检查完整快照。 */
                    var candidates = new ArrayList<NativeBatch.Candidate>();
                    for (var entry : unique.values()) {
                        checkDeadline(deadline);
                        var key = store.publish(entry.staged()); published.add(key);
                        candidates.add(new NativeBatch.Candidate(entry.name(), entry.elf(), entry.staged(), key));
                    }
                    var committed = writer.commit(app, user, version, candidates, expected, deadline);
                    metadataCommitted = true;
                    for (var key : published) if (!files.existsByStorageKeyAndDeletedAtIsNull(key)) store.retire(key);
                    for (var key : committed.retiredKeys()) store.retire(key);
                    log.info("SO 批次已保存：新建={} 替换={} 相同={}", committed.result().createdCount(), committed.result().replacedCount(), committed.result().unchangedCount());
                    return committed.result();
                } finally {
                    for (var entry : unique.values()) store.discard(entry.staged());
                }
            }
        } catch (com.shanshui.apmserver.symbol.api.NativeBatchConflictException | SymbolValidationException failure) {
            for (var key : published) store.retire(key);
            log.warn("SO 批次未提交：校验或确认冲突");
            if (failure instanceof SymbolValidationException invalid && "MAPPING_TOO_LARGE".equals(invalid.getCode())) {
                throw new SymbolValidationException("SYMBOL_ARCHIVE_TOO_LARGE", "SO 文件或归档超过字节预算", 413);
            }
            throw failure;
        } catch (org.springframework.dao.DataIntegrityViolationException rollback) {
            for (var key : published) store.retire(key);
            throw new SymbolValidationException("SYMBOL_BATCH_CONFLICT", "模块登记已变化，请重新上传核对", 409);
        } catch (ArchiveLimitException exceeded) {
            for (var key : published) store.retire(key);
            throw new SymbolValidationException("SYMBOL_ARCHIVE_TOO_LARGE", "SO 解压字节超过预算", 413);
        } catch (ElfValidationException invalid) {
            for (var key : published) store.retire(key);
            throw new SymbolValidationException(invalid.code(), "SO 内容不满足登记要求", 422);
        } catch (java.util.zip.ZipException invalid) {
            if (!metadataCommitted) for (var key : published) store.retire(key);
            log.warn("SO 批次未提交：归档内容格式无效");
            throw new SymbolValidationException("INVALID_SYMBOL_ARCHIVE", "ZIP 内容格式无效", 422);
        } catch (IOException unavailable) {
            if (!metadataCommitted) for (var key : published) store.retire(key);
            log.warn("SO 校验或清理暂不可用：类别={} 已提交={}", unavailable.getClass().getSimpleName(), metadataCommitted);
            throw new SymbolStoreUnavailableException(unavailable);
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 不可用", impossible);
        } catch (RuntimeException unknown) {
            log.warn("SO 批次存储结果未确认，保留可能已引用文件");
            throw new SymbolStoreUnavailableException(unknown);
        } finally { store.discard(zip); }
    }
    /** 受限显示名称不作为路径；控制字符已由归档校验拒绝。 */
    private static String safeName(String name) {
        String value = name.substring(name.lastIndexOf('/') + 1);
        return value.length() > 255 ? value.substring(0, 255) : value;
    }
    /** 统一单调期限，所有拷贝循环共同执行。 */
    private static void checkDeadline(long deadline) throws IOException {
        if (Thread.currentThread().isInterrupted() || System.nanoTime() >= deadline) throw new InterruptedIOException("SO 接收已中止");
    }
    /** 接收后的拷贝也按实际读取检查期限，不信任声明大小。 */
    private static final class DeadlineInput extends FilterInputStream {
        /** 许可取得时固定的期限。 */
        private final long deadline;
        /** 包装本次流。 */
        private DeadlineInput(InputStream stream, long deadline) { super(stream); this.deadline = deadline; }
        /** 每次读取前检查中止。 */
        @Override public int read(byte[] buffer, int offset, int length) throws IOException {
            checkDeadline(deadline); return in.read(buffer, offset, length);
        }
        /** 单字节读取同样受限。 */
        @Override public int read() throws IOException { checkDeadline(deadline); return in.read(); }
    }
}
