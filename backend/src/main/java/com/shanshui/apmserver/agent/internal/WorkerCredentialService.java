package com.shanshui.apmserver.agent.internal;

import com.shanshui.apmserver.identity.api.AppAccessControl;
import com.shanshui.apmserver.platform.api.QueryValidationException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

/** 管理独立 Worker 凭据，验证时间统一使用 PostgreSQL 时钟。 */
@Service
public class WorkerCredentialService {
    /** 只接受独立执行凭据格式。 */
    private static final Pattern FORMAT = Pattern.compile("^apm_aw_[A-Za-z0-9_-]{43}$");
    /** 高熵随机源。 */
    private final SecureRandom random = new SecureRandom();
    /** 网页管理员授权。 */
    private final AppAccessControl access;
    /** 独立管理表访问。 */
    private final JdbcTemplate jdbc;
    /** 新建身份随功能开关关闭，已有撤销及查询保持可用。 */
    private final boolean enabled;

    /** 注入授权与数据库。 */
    public WorkerCredentialService(AppAccessControl access, JdbcTemplate jdbc,
            @Value("${apm.agent.analysis.enabled:false}") boolean enabled) {
        this.access = access;
        this.jdbc = jdbc;
        this.enabled = enabled;
    }

    /** 公开管理元数据，不包含可逆秘密或摘要。 */
    public record Metadata(/** 凭据 ID。 */ UUID credentialId, /** 应用。 */ UUID appId,
            /** 管理名称。 */ String name, /** 显示前缀。 */ String displayPrefix,
            /** 创建时间。 */ Instant createdAt, /** 数据库到期时间。 */ Instant expiresAt,
            /** 撤销时间，可空。 */ Instant revokedAt) {}

    /** 创建响应仅此一次包含秘密，任何后续查询不能恢复。 */
    public record Created(/** 管理元数据。 */ Metadata metadata, /** 一次展示值。 */ String credential) {}

    /** 受信身份仅保存服务端归属；不持有输入秘密。 */
    public record Identity(/** 身份 ID。 */ UUID credentialId, /** 唯一应用。 */ UUID appId) {}

    /** 只有管理员能创建；应用锁限制活动凭据并发数量。 */
    @Transactional
    public Created create(UUID appId, String name, Authentication authentication) {
        access.requireEdit(appId, authentication);
        if (!enabled) throw new QueryValidationException("ANALYSIS_DISABLED", "分析功能未开启", 503);
        // 名称只作为管理备注，不外发模型。
        String normalized = name == null ? "" : name.strip();
        if (normalized.isEmpty() || normalized.length() > 100) throw new QueryValidationException("INVALID_WORKER_NAME", "凭据名称长度需为 1 到 100", 400);
        jdbc.query("SELECT pg_advisory_xact_lock(hashtextextended(?,0))", row -> {}, appId.toString());
        // 仅数本应用尚可使用的凭据。
        Integer active = jdbc.queryForObject("SELECT count(*) FROM analysis_worker_credential WHERE app_id=? AND revoked_at IS NULL AND expires_at>clock_timestamp()", Integer.class, appId);
        if (active >= 10) throw new QueryValidationException("WORKER_CREDENTIAL_LIMIT", "应用最多十个活动 Worker 凭据", 429);
        // 生成 256 bit 随机秘密，数据库只保留 SHA-256。
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        // 与查询 Token 的前缀及格式严格分离。
        String secret = "apm_aw_" + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        // 管理对象标识独立于秘密。
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO analysis_worker_credential(credential_id,app_id,name,token_sha256,display_prefix,created_by) VALUES (?,?,?,?,?,?)",
                id, appId, normalized, AnalysisEvidence.digest(secret), secret.substring(0, 16), access.requireUserId(authentication));
        return new Created(metadata(appId, id), secret);
    }

    /** 有界管理员列表在数据库按应用分页，不回传摘要。 */
    public List<Metadata> list(UUID appId, Authentication authentication, int page, int size) {
        access.requireEdit(appId, authentication);
        if (page < 0 || page > 1000 || size < 1 || size > 100) throw new QueryValidationException("INVALID_WORKER_PAGE", "分页参数无效", 400);
        return jdbc.query("SELECT * FROM analysis_worker_credential WHERE app_id=? ORDER BY created_at DESC,credential_id DESC LIMIT ? OFFSET ?", this::row, appId, size, (long) page * size);
    }

    /** 撤销可重复，跨应用对象统一按不存在处理。 */
    @Transactional
    public void revoke(UUID appId, UUID id, Authentication authentication) {
        access.requireEdit(appId, authentication);
        if (jdbc.update("UPDATE analysis_worker_credential SET revoked_at=COALESCE(revoked_at,clock_timestamp()) WHERE app_id=? AND credential_id=?", appId, id) != 1) {
            throw new QueryValidationException("WORKER_CREDENTIAL_NOT_FOUND", "Worker 凭据不存在", 404);
        }
    }

    /** 每次 Worker HTTP 请求重新核对数据库时间、撤销及限定归属。 */
    public Identity authenticate(String secret) {
        if (secret == null || !FORMAT.matcher(secret).matches()) throw invalid();
        // 索引按唯一摘要定位；只返回非敏感身份列。
        List<Identity> rows = jdbc.query("SELECT credential_id,app_id FROM analysis_worker_credential WHERE token_sha256=? AND revoked_at IS NULL AND expires_at>clock_timestamp()",
                (row, number) -> new Identity(row.getObject("credential_id", UUID.class), row.getObject("app_id", UUID.class)), AnalysisEvidence.digest(secret));
        if (rows.isEmpty()) throw invalid();
        return rows.getFirst();
    }

    /** 根据所属应用返回创建时的公开记录。 */
    private Metadata metadata(UUID appId, UUID id) {
        return jdbc.query("SELECT * FROM analysis_worker_credential WHERE app_id=? AND credential_id=?", this::row, appId, id).getFirst();
    }

    /** 数据库行仅映射安全字段，隐藏摘要与创建者身份。 */
    private Metadata row(java.sql.ResultSet row, int number) throws java.sql.SQLException {
        // 可空撤销时间单独处理。
        var revoked = row.getTimestamp("revoked_at");
        return new Metadata(row.getObject("credential_id", UUID.class), row.getObject("app_id", UUID.class),
                row.getString("name"), row.getString("display_prefix"), row.getTimestamp("created_at").toInstant(), row.getTimestamp("expires_at").toInstant(), revoked == null ? null : revoked.toInstant());
    }

    /** 统一错误不区分未知、到期或撤销，避免凭据存在性探测。 */
    private QueryValidationException invalid() { return new QueryValidationException("WORKER_CREDENTIAL_INVALID", "Worker 凭据无效", 401); }
}
