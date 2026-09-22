package com.shanshui.apmserver.symbol.internal.domain;

import com.shanshui.apmserver.symbol.api.SymbolFileMetadata;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/** 应用构建当前生效的 mapping 元数据。 */
@Entity
@Table(name = "app_symbol_file")
public class SymbolFileEntity {

    /** 符号表记录标识。 */
    @Id
    @Column(name = "symbol_id", nullable = false)
    private UUID symbolId;
    /** 所属应用标识。 */
    @Column(name = "app_id", nullable = false)
    private UUID appId;
    /** 精确匹配的构建标识。 */
    @Column(name = "build_id", nullable = false, length = 128)
    private String buildId;
    /** 当前替换版本，从一开始递增。 */
    @Column(nullable = false)
    private int revision;
    /** 服务端生成的不可变存储键。 */
    @Column(name = "storage_key", nullable = false, unique = true, length = 160)
    private String storageKey;
    /** 用户上传时提供的原始文件名。 */
    @Column(name = "original_filename", nullable = false, length = 255)
    private String originalFilename;
    /** 文件字节数。 */
    @Column(name = "size_bytes", nullable = false)
    private long sizeBytes;
    /** 小写 SHA-256 摘要。 */
    @Column(nullable = false, length = 64)
    private String sha256;
    /** 最后一次上传或替换的用户。 */
    @Column(name = "uploaded_by", nullable = false)
    private UUID uploadedBy;
    /** 首次上传时间。 */
    @Column(name = "uploaded_at", nullable = false)
    private Instant uploadedAt;
    /** 当前版本更新时间。 */
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /** JPA 需要的无参构造函数。 */
    protected SymbolFileEntity() {
    }

    /** 创建首次注册的符号表记录。 */
    public SymbolFileEntity(UUID symbolId, UUID appId, String buildId, int revision,
                            String storageKey, String originalFilename, long sizeBytes,
                            String sha256, UUID uploadedBy, Instant uploadedAt, Instant updatedAt) {
        this.symbolId = symbolId;
        this.appId = appId;
        this.buildId = buildId;
        this.revision = revision;
        this.storageKey = storageKey;
        this.originalFilename = originalFilename;
        this.sizeBytes = sizeBytes;
        this.sha256 = sha256;
        this.uploadedBy = uploadedBy;
        this.uploadedAt = uploadedAt;
        this.updatedAt = updatedAt;
    }

    /** 以新版本替换当前记录。 */
    public void replace(String newStorageKey, String newOriginalFilename, long newSizeBytes,
                        String newSha256, UUID newUploadedBy, Instant newUpdatedAt) {
        this.revision++;
        this.storageKey = newStorageKey;
        this.originalFilename = newOriginalFilename;
        this.sizeBytes = newSizeBytes;
        this.sha256 = newSha256;
        this.uploadedBy = newUploadedBy;
        this.updatedAt = newUpdatedAt;
    }

    /** 返回当前符号表的网页元数据。 */
    public SymbolFileMetadata metadata() {
        return new SymbolFileMetadata(symbolId, appId, buildId, revision, originalFilename,
                sizeBytes, sha256, uploadedBy, uploadedAt, updatedAt);
    }

    /** 返回符号表记录标识。 */
    public UUID getSymbolId() { return symbolId; }
    /** 返回所属应用标识。 */
    public UUID getAppId() { return appId; }
    /** 返回构建标识。 */
    public String getBuildId() { return buildId; }
    /** 返回当前版本。 */
    public int getRevision() { return revision; }
    /** 返回服务端存储键。 */
    public String getStorageKey() { return storageKey; }
    /** 返回原始文件名。 */
    public String getOriginalFilename() { return originalFilename; }
    /** 返回文件大小。 */
    public long getSizeBytes() { return sizeBytes; }
    /** 返回 SHA-256。 */
    public String getSha256() { return sha256; }
    /** 返回上传用户。 */
    public UUID getUploadedBy() { return uploadedBy; }
    /** 返回首次上传时间。 */
    public Instant getUploadedAt() { return uploadedAt; }
    /** 返回更新时间。 */
    public Instant getUpdatedAt() { return updatedAt; }
}
