package com.shanshui.apmserver.symbol.internal.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/** 符号表替换审计记录，只保存版本和摘要。 */
@Entity
@Table(name = "app_symbol_file_audit")
public class SymbolAuditEntity {

    /** 审计记录标识。 */
    @Id
    @Column(name = "audit_id", nullable = false)
    private UUID auditId;
    /** 被替换的符号表记录。 */
    @Column(name = "symbol_id", nullable = false)
    private UUID symbolId;
    /** 所属应用。 */
    @Column(name = "app_id", nullable = false)
    private UUID appId;
    /** 构建标识。 */
    @Column(name = "build_id", nullable = false, length = 128)
    private String buildId;
    /** 被替换前的版本。 */
    @Column(name = "old_revision")
    private Integer oldRevision;
    /** 被替换前的摘要。 */
    @Column(name = "old_sha256", length = 64)
    private String oldSha256;
    /** 生效后的版本。 */
    @Column(name = "new_revision", nullable = false)
    private int newRevision;
    /** 生效后的摘要。 */
    @Column(name = "new_sha256", nullable = false, length = 64)
    private String newSha256;
    /** 执行操作的用户。 */
    @Column(name = "changed_by", nullable = false)
    private UUID changedBy;
    /** 操作时间。 */
    @Column(name = "changed_at", nullable = false)
    private Instant changedAt;

    /** JPA 需要的无参构造函数。 */
    protected SymbolAuditEntity() {
    }

    /** 创建符号表替换审计。 */
    public SymbolAuditEntity(UUID auditId, UUID symbolId, UUID appId, String buildId,
                             Integer oldRevision, String oldSha256, int newRevision,
                             String newSha256, UUID changedBy, Instant changedAt) {
        this.auditId = auditId;
        this.symbolId = symbolId;
        this.appId = appId;
        this.buildId = buildId;
        this.oldRevision = oldRevision;
        this.oldSha256 = oldSha256;
        this.newRevision = newRevision;
        this.newSha256 = newSha256;
        this.changedBy = changedBy;
        this.changedAt = changedAt;
    }
}
