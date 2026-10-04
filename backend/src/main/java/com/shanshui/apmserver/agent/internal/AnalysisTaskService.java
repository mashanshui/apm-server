package com.shanshui.apmserver.agent.internal;

import com.shanshui.apmserver.crash.api.CrashAnalysisSnapshot;
import com.shanshui.apmserver.crash.api.CrashEventDetailResponse;
import com.shanshui.apmserver.crash.api.CrashQueries;
import com.shanshui.apmserver.identity.api.AppAccessControl;
import com.shanshui.apmserver.platform.api.QueryValidationException;
import com.shanshui.apmserver.symbol.api.AnalysisSymbolVersions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

/** 单事件创建和准备；耗时 Retrace 在数据库事务之外执行。 */
@Service
public class AnalysisTaskService {
    /** 网页授权边界。 */
    private final AppAccessControl access;
    /** 唯一的 Crash 查询公开边界。 */
    private final CrashQueries crashes;
    /** 发布时的短事务符号版本核验。 */
    private final AnalysisSymbolVersions symbols;
    /** PostgreSQL 管理数据访问。 */
    private final JdbcTemplate jdbc;
    /** 短事务模板避免自调用事务注解失效。 */
    private final TransactionTemplate transaction;
    /** Spring 的 JSON 编码器。 */
    private final ObjectMapper mapper;
    /** 单份内容上限，可调小，不能扩大超过迁移约束。 */
    private final int maxEvidenceBytes;
    /** 部署开关，关闭时禁止所有新的准备工作。 */
    private final boolean enabled;

    /** 列表只读取摘要与证据版本，不把完整 JSON 带入分页响应内存。 */
    private static final String TASK_COLUMNS = "task_id,app_id,event_id,fingerprint,created_by,created_at,state,block_reason,evidence_id,evidence_sha256,content_expired,(evidence->>'schemaVersion')::integer AS evidence_schema_version";

    /** 注入公开域边界、事务与部署限制。 */
    public AnalysisTaskService(AppAccessControl access, CrashQueries crashes,
            AnalysisSymbolVersions symbols, JdbcTemplate jdbc, org.springframework.transaction.PlatformTransactionManager manager,
            ObjectMapper mapper, @Value("${apm.agent.analysis.max-evidence-bytes:1048576}") int maxEvidenceBytes,
            @Value("${apm.agent.analysis.enabled:false}") boolean enabled) {
        this.access = access;
        this.crashes = crashes;
        this.symbols = symbols;
        this.jdbc = jdbc;
        this.transaction = new TransactionTemplate(manager);
        this.mapper = mapper;
        this.maxEvidenceBytes = maxEvidenceBytes;
        this.enabled = enabled;
        if (maxEvidenceBytes < 1 || maxEvidenceBytes > 1048576) throw new IllegalArgumentException("证据上限无效");
    }

    /** 幂等创建单事件任务，不查询同 Issue 的其他 Crash。 */
    public AnalysisTaskView create(UUID appId, String eventId, UUID key, Authentication authentication) {
        requireEnabled();
        access.requireAnalysis(appId, authentication);
        // 重复请求先核对原输入，避免无意义地重复 Retrace。
        List<AnalysisTaskView> existing = jdbc.query("SELECT " + TASK_COLUMNS + " FROM crash_analysis_task WHERE app_id=? AND idempotency_key=?",
                row(), appId, key);
        if (!existing.isEmpty()) return sameInput(existing.getFirst(), eventId);
        // 只有被选中的事件被读取，先取得不带还原副作用的构建与 Issue 信息。
        CrashEventDetailResponse raw = crashes.rawEvent(appId, eventId);
        if (raw.rawCrash() == null || !"jvm".equals(raw.rawCrash().kind()) || !Boolean.TRUE.equals(raw.rawCrash().fatal())) {
            throw new QueryValidationException("UNSUPPORTED_ANALYSIS_EVENT", "仅支持 JVM fatal Crash", 400);
        }
        // 短事务创建初始阻断任务，准备期间仍能取消或显式查看。
        UUID taskId = transaction.execute(status -> {
            lockApp(appId);
            access.requireAnalysis(appId, authentication);
            List<AnalysisTaskView> repeated = jdbc.query("SELECT " + TASK_COLUMNS + " FROM crash_analysis_task WHERE app_id=? AND idempotency_key=?", row(), appId, key);
            if (!repeated.isEmpty()) return sameInput(repeated.getFirst(), eventId).taskId();
            // 按应用限定活动数量，和幂等插入使用相同数据库事务锁。
            Integer active = jdbc.queryForObject("SELECT count(*) FROM crash_analysis_task WHERE app_id=? AND state IN ('BLOCKED','READY','RUNNING','CANCELLING')", Integer.class, appId);
            if (active >= 3) throw new QueryValidationException("ANALYSIS_TASK_LIMIT", "应用最多三个未结束分析任务", 429);
            // 独立随机任务 ID；只绑定所选事件，不登记源码版本。
            UUID id = UUID.randomUUID();
            jdbc.update("INSERT INTO crash_analysis_task(task_id,app_id,event_id,idempotency_key,created_by,fingerprint,state,block_reason) "
                            + "VALUES (?,?,?,?,?,?,'BLOCKED','EVIDENCE_PREPARING')", id, appId, eventId, key,
                    access.requireUserId(authentication), raw.fingerprint());
            return id;
        });
        // 并发幂等重试不会覆盖已经发布的证据。
        prepare(appId, taskId, authentication);
        return get(appId, taskId, authentication);
    }

    /** 显式重新检查缺失前提，只允许尚未拥有证据的阻断任务。 */
    public AnalysisTaskView recheck(UUID appId, UUID taskId, Authentication authentication) {
        requireEnabled();
        transaction.executeWithoutResult(status -> {
            lockApp(appId);
            access.requireAnalysis(appId, authentication);
            AnalysisTaskView task = find(appId, taskId);
            if (!"BLOCKED".equals(task.state()) || task.evidenceId() != null) {
                throw new QueryValidationException("ANALYSIS_STATE_CONFLICT", "当前任务不能重新检查，请新建分析", 409);
            }
            // 新模式不允许重新执行旧冻结输入；未冻结准备失败可显式重检。
            jdbc.update("UPDATE crash_analysis_task SET preparation_generation=preparation_generation+1,block_reason='EVIDENCE_PREPARING' WHERE task_id=? AND app_id=?",
                    taskId, appId);
        });
        prepare(appId, taskId, authentication);
        return get(appId, taskId, authentication);
    }

    /** 事务外还原，然后用准备代次及版本条件发布完整证据。 */
    private void prepare(UUID appId, UUID taskId, Authentication authentication) {
        // 原子读取本次任务投影与准备代次，防止两次读取之间被重检替换。
        List<Preparation> rows = jdbc.query("SELECT " + TASK_COLUMNS + ",preparation_generation FROM crash_analysis_task WHERE app_id=? AND task_id=?",
                (row, number) -> new Preparation(row().mapRow(row, number), row.getLong("preparation_generation")), appId, taskId);
        if (rows.isEmpty()) return;
        // 只发布该代次，异常也不能污染并发的新代次。
        Preparation preparation = rows.getFirst();
        try {
            prepareVersion(appId, preparation, authentication);
        } catch (RuntimeException failure) {
            // 保存稳定原因，不将异常消息或原始数据写入任务日志。
            jdbc.update("UPDATE crash_analysis_task SET block_reason='PREPARATION_FAILED' WHERE app_id=? AND task_id=? AND state='BLOCKED' AND preparation_generation=? AND block_reason='EVIDENCE_PREPARING'",
                    appId, taskId, preparation.generation());
        }
    }

    /** 任务和准备代次来自同一条 PostgreSQL 记录。 */
    private record Preparation(/** 内部任务投影。 */ AnalysisTaskView task, /** 写回代次。 */ long generation) {}

    /** 发布指定代次，不在失败后自动扩大权限或调用模型。 */
    private void prepareVersion(UUID appId, Preparation preparation, Authentication authentication) {
        // 只有当前准备代次可以发布；重复创建返回原任务而不自动重新检查缺失项。
        AnalysisTaskView task = preparation.task();
        // 当前准备对象标识。
        UUID taskId = task.taskId();
        if (!"BLOCKED".equals(task.state()) || !"EVIDENCE_PREPARING".equals(task.blockReason())) return;
        // 记录代次后只还原当前事件；缺少 mapping 时保留原始材料。
        long generation = preparation.generation();
        CrashAnalysisSnapshot snapshot = crashes.analysisSnapshot(appId, task.eventId());
        // 输入身份必须保持当前应用、事件和 Issue 归组。
        String reason = null;
        if (!task.eventId().equals(snapshot.detail().eventId()) || !appId.equals(snapshot.detail().appId())
                || !task.fingerprint().equals(snapshot.detail().fingerprint())) reason = "EVENT_IDENTITY_CHANGED";
        // 每份内容与摘要使用独立随机证据 ID。
        UUID evidenceId = UUID.randomUUID();
        // 保留原始发送字节，与 JSONB 查询投影同时保存，避免 JSONB 重排影响摘要。
        String evidence = reason == null ? AnalysisEvidence.create(mapper, evidenceId, snapshot) : null;
        if (evidence != null && evidence.getBytes(StandardCharsets.UTF_8).length > maxEvidenceBytes) reason = "EVIDENCE_TOO_LARGE";
        if (evidence != null && reason == null) {
            // PostgreSQL JSONB 会添加格式空格，对其真实投影也独立检查大小。
            Integer storedBytes = jdbc.queryForObject("SELECT octet_length((?::jsonb)::text)", Integer.class, evidence);
            if (storedBytes > maxEvidenceBytes) reason = "EVIDENCE_TOO_LARGE";
        }
        // Lambda 使用本次计算出的固定原因。
        String failureReason = reason;
        transaction.executeWithoutResult(status -> {
            lockApp(appId);
            access.requireAnalysis(appId, authentication);
            // 同次还原使用的符号版本仍需核验，不把旧文本冒充新 mapping。
            String blocked = failureReason;
            if (blocked == null && snapshot.detail().symbolFileId() != null
                    && !symbols.lockAndMatch(appId, snapshot.detail().buildId(), snapshot.detail().symbolFileId(),
                    snapshot.detail().symbolFileRevision(), snapshot.mappingSha256())) blocked = "SYMBOL_VERSION_CHANGED";
            if (blocked != null) {
                jdbc.update("UPDATE crash_analysis_task SET block_reason=? WHERE app_id=? AND task_id=? AND state='BLOCKED' AND preparation_generation=?",
                        blocked, appId, taskId, generation);
            } else {
                jdbc.update("UPDATE crash_analysis_task SET state='READY',block_reason=NULL,evidence_id=?,evidence=?::jsonb,evidence_text=?,evidence_sha256=? "
                                + "WHERE app_id=? AND task_id=? AND state='BLOCKED' AND preparation_generation=?", evidenceId, evidence, evidence,
                        AnalysisEvidence.digest(evidence), appId, taskId, generation);
            }
        });
    }

    /** 取消准备任务立即结束；运行任务必须等待整个环境停止确认。 */
    public AnalysisTaskView cancel(UUID appId, UUID taskId, Authentication authentication) {
        transaction.executeWithoutResult(status -> {
            lockApp(appId);
            access.requireAnalysis(appId, authentication);
            // 创建者可取消自己的任务，其他成员必须具有管理员权限。
            AnalysisTaskView task = find(appId, taskId);
            if (!task.createdBy().equals(access.requireUserId(authentication))) access.requireEdit(appId, authentication);
            if (List.of("SUCCEEDED", "FAILED", "CANCELLED").contains(task.state())) return;
            // 改变准备代次，任何迟到的证据发布失效。
            String next = List.of("RUNNING", "CANCELLING").contains(task.state()) ? "CANCELLING" : "CANCELLED";
            jdbc.update("UPDATE crash_analysis_task SET state=?,terminal_at=CASE WHEN ?='CANCELLED' THEN clock_timestamp() ELSE NULL END,block_reason=NULL,preparation_generation=preparation_generation+1 WHERE app_id=? AND task_id=?", next, next, appId, taskId);
        });
        return get(appId, taskId, authentication);
    }

    /** 仅对有完整证据且旧环境已停止的失败/取消任务显式重试。 */
    public AnalysisTaskView retry(UUID appId, UUID taskId, Authentication authentication) {
        requireEnabled();
        transaction.executeWithoutResult(status -> {
            lockApp(appId);
            access.requireAnalysis(appId, authentication);
            // 保留原冻结证据，不将管理员后续更正混进同一任务。
            AnalysisTaskView task = find(appId, taskId);
            if (!List.of("FAILED", "CANCELLED").contains(task.state()) || task.evidenceId() == null || task.contentExpired()) {
                throw new QueryValidationException("ANALYSIS_STATE_CONFLICT", "当前任务不可重试，请新建分析", 409);
            }
            if (!Integer.valueOf(2).equals(jdbc.queryForObject("SELECT (evidence->>'schemaVersion')::integer FROM crash_analysis_task WHERE app_id=? AND task_id=?", Integer.class, appId, taskId))) {
                throw new QueryValidationException("ANALYSIS_CONFIG_MISMATCH", "旧任务仅供历史查看，请创建当前代码任务", 409);
            }
            // 旧执行即使失联，也不能默认已停止。
            Integer unknown = jdbc.queryForObject("SELECT count(*) FROM crash_analysis_run WHERE app_id=? AND task_id=? AND stop_confirmed=false", Integer.class, appId, taskId);
            if (unknown > 0) throw new QueryValidationException("ANALYSIS_STOP_UNCONFIRMED", "旧执行环境停止尚未确认", 409);
            Integer active = jdbc.queryForObject("SELECT count(*) FROM crash_analysis_task WHERE app_id=? AND state IN ('BLOCKED','READY','RUNNING','CANCELLING')", Integer.class, appId);
            if (active >= 3) throw new QueryValidationException("ANALYSIS_TASK_LIMIT", "应用最多三个未结束分析任务", 429);
            jdbc.update("UPDATE crash_analysis_task SET state='READY',terminal_at=NULL,block_reason=NULL,current_run_id=NULL WHERE app_id=? AND task_id=?", appId, taskId);
        });
        return get(appId, taskId, authentication);
    }

    /** 领取前仅提供当前任务摘要，不暴露材料正文或本机路径。 */
    public record Inspection(/** 所属应用的当前任务。 */ AnalysisTaskView task) {}

    /** Worker 归属由应用身份确定，不需要仓库或执行类型参数。 */
    public Inspection inspect(WorkerCredentialService.Identity identity, UUID taskId) {
        return new Inspection(find(identity.appId(), taskId));
    }

    /** 应用成员读取所属任务，不提供完整设备身份或密钥。 */
    public AnalysisTaskView get(UUID appId, UUID taskId, Authentication authentication) {
        access.requireView(appId, authentication);
        return find(appId, taskId);
    }

    /** 事件下任务历史在数据库过滤、排序并限制返回条数。 */
    public List<AnalysisTaskView> list(UUID appId, String eventId, Authentication authentication, int page, int size) {
        access.requireView(appId, authentication);
        if (page < 0 || page > 1000 || size < 1 || size > 100) throw new QueryValidationException("INVALID_ANALYSIS_PAGE", "分页参数无效", 400);
        return jdbc.query("SELECT " + TASK_COLUMNS + " FROM crash_analysis_task WHERE app_id=? AND event_id=? ORDER BY created_at DESC,task_id DESC LIMIT ? OFFSET ?",
                row(), appId, eventId, size, (long) page * size);
    }

    /** 只按授权应用和 ID 查找，未知与其他应用任务统一为不存在。 */
    private AnalysisTaskView find(UUID appId, UUID taskId) {
        List<AnalysisTaskView> rows = jdbc.query("SELECT " + TASK_COLUMNS + " FROM crash_analysis_task WHERE app_id=? AND task_id=?", row(), appId, taskId);
        if (rows.isEmpty()) throw new QueryValidationException("ANALYSIS_TASK_NOT_FOUND", "分析任务不存在", 404);
        return rows.getFirst();
    }

    /** 核对幂等键的原始事件输入。 */
    private AnalysisTaskView sameInput(AnalysisTaskView existing, String eventId) {
        if (!existing.eventId().equals(eventId)) throw new QueryValidationException("ANALYSIS_IDEMPOTENCY_CONFLICT", "幂等键已用于其他事件", 409);
        return existing;
    }

    /** 与构建更正使用相同应用事务锁，所有查询仍按应用过滤。 */
    private void lockApp(UUID appId) {
        jdbc.query("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))", rs -> {}, appId.toString());
    }

    /** 响应只包含任务摘要，不返回材料正文或源码配置。 */
    private RowMapper<AnalysisTaskView> row() {
        return (row, number) -> new AnalysisTaskView(row.getObject("task_id", UUID.class), row.getObject("app_id", UUID.class),
                row.getString("event_id"), row.getString("fingerprint"), row.getObject("created_by", UUID.class),
                row.getTimestamp("created_at").toInstant(), row.getString("state"), row.getString("block_reason"),
                row.getObject("evidence_schema_version", Integer.class), row.getObject("evidence_id", UUID.class), row.getString("evidence_sha256"), row.getBoolean("content_expired"));
    }

    /** 服务层同样防止绕过控制器的关闭状态。 */
    private void requireEnabled() {
        if (!enabled) throw new QueryValidationException("ANALYSIS_DISABLED", "分析功能未开启", 503);
    }
}
