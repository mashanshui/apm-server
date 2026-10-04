package com.shanshui.apmserver.agent.internal;

import com.shanshui.apmserver.platform.api.QueryValidationException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.Set;
import java.nio.charset.StandardCharsets;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.DeserializationFeature;
import jakarta.validation.Validator;
import com.shanshui.apmserver.identity.api.AppAccessControl;
import org.springframework.security.core.Authentication;

/** 原子指定任务领取及短期租约，所有时间判断采用数据库时钟。 */
@Service
public class AnalysisRunService {
    /** 结构化正文编码与严格解析。 */
    private final ObjectMapper mapper;
    /** 对嵌套记录执行 Jakarta 约束验证。 */
    private final Validator validator;
    /** 网页停止核验与历史读取授权。 */
    private final AppAccessControl access;
    /** 所有运行元数据仅在 PostgreSQL 管理表持久化。 */
    private final JdbcTemplate jdbc;
    /** 运行 Token 不复用 Worker 长期凭据。 */
    private final SecureRandom random = new SecureRandom();
    /** 到期状态先提交，再向调用方返回失效错误，避免异常回滚期限处理。 */
    private final TransactionTemplate transaction;
    /** 新分配和续租的功能开关。 */
    private final boolean enabled;

    /** 注入管理数据及部署开关。 */
    public AnalysisRunService(JdbcTemplate jdbc, AppAccessControl access, PlatformTransactionManager manager, ObjectMapper mapper, Validator validator, @Value("${apm.agent.analysis.enabled:false}") boolean enabled) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.validator = validator;
        this.access = access;
        this.transaction = new TransactionTemplate(manager);
        this.enabled = enabled;
    }

    /** 非敏感 Run 状态，用数据库当前时间校准保守本地租约。 */
    public record RunView(/** 分配 ID。 */ UUID runId, /** 任务 ID。 */ UUID taskId,
            /** 尝试序号。 */ int attempt, /** 当前执行状态。 */ String state,
            /** 任务状态包含取消意图。 */ String taskState, /** 分配代次。 */ long leaseGeneration,
            /** 租约到期。 */ Instant leaseExpiresAt, /** 固定运行总期限。 */ Instant deadlineAt,
            /** 数据库时钟。 */ Instant serverNow, /** 是否已确认整个环境停止。 */ boolean stopConfirmed,
            /** 失败稳定码，可空。 */ String errorCode,
            /** 本次任务材料工具是否关闭。 */ boolean localToolsStopped,
            /** 宿主停止状态，不能由模型自报确认。 */ String hostStopState,
            /** 历史源码快照 ID，新宿主直接读取 Run 为空。 */ UUID snapshotId) {}

    /** 仅向原分配 Worker 返回活动租约秘密，元数据查询不会包含它。 */
    public record Claimed(/** 分配状态。 */ RunView run, /** 活动 Run 的随机租约 Token。 */ String leaseToken) {}

    /** Worker 请求必须携带当前 Run 分配代次与租约 Token。 */
    public record Lease(/** 本分配代次。 */ long generation, /** 独立租约 Token。 */ String token) {}

    /** 同 Worker/请求 ID 重试返回原分配，不能用于其他任务。 */
    @Transactional
    public Claimed claim(WorkerCredentialService.Identity identity, UUID taskId, UUID requestId) {
        requireEnabled();
        if (requestId == null) throw new QueryValidationException("INVALID_ANALYSIS_REQUEST", "领取必须提供请求 ID", 400);
        lockApp(identity.appId());
        lockCredential(identity);
        // 稳定领取请求先查原分配，不再重复生成 Run。
        List<UUID> existing = jdbc.queryForList("SELECT run_id FROM crash_analysis_run WHERE credential_id=? AND request_id=?", UUID.class, identity.credentialId(), requestId);
        if (!existing.isEmpty()) {
            // 原分配与请求输入必须一致，即使已经结束也不分配新尝试。
            RunView run = view(identity, existing.getFirst());
            if (!run.taskId().equals(taskId) || run.snapshotId() != null) throw conflict("ANALYSIS_CLAIM_CONFLICT", "领取请求已用于其他任务");
            expire(run);
            return claimed(identity, run.runId());
        }
        // 按凭据应用锁定任务，未知与跨应用统一拒绝。
        List<String> states = jdbc.queryForList("SELECT state FROM crash_analysis_task WHERE app_id=? AND task_id=? FOR UPDATE", String.class, identity.appId(), taskId);
        if (states.isEmpty()) throw notFound();
        if (!"READY".equals(states.getFirst())) throw conflict("ANALYSIS_NOT_READY", "任务当前不可领取");
        // 旧冻结任务不能偷偷更换分析策略。
        Integer schema = jdbc.queryForObject("SELECT (evidence->>'schemaVersion')::integer FROM crash_analysis_task WHERE app_id=? AND task_id=?", Integer.class, identity.appId(), taskId);
        if (!Integer.valueOf(2).equals(schema)) throw conflict("ANALYSIS_CONFIG_MISMATCH", "请创建宿主分析新任务，不能改写旧冻结输入");
        // 同一执行凭据同时最多一个运行环境，未停止的历史也禁止新分配。
        Boolean active = jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM crash_analysis_run WHERE credential_id=? AND (state='RUNNING' OR stop_confirmed=false))", Boolean.class, identity.credentialId());
        if (Boolean.TRUE.equals(active)) throw conflict("WORKER_BUSY", "Worker 仍有未确认停止的执行环境");
        // 只有应用锁持有者可以递增尝试，证据版本保持原任务不变。
        Integer attempt = jdbc.queryForObject("SELECT COALESCE(max(attempt),0)+1 FROM crash_analysis_run WHERE app_id=? AND task_id=?", Integer.class, identity.appId(), taskId);
        // 每次分配产生独立环境标识和随机短期租约。
        UUID runId = UUID.randomUUID();
        // 随机秘密只提供给原分配凭据；终止后清除可逆值，保留摘要用于幂等确认。
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        // 256 bit 随机 Token。
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        jdbc.update("INSERT INTO crash_analysis_run(run_id,task_id,app_id,credential_id,request_id,attempt,state,lease_generation,lease_token,lease_sha256,lease_expires_at,deadline_at,host_stop_state) "
                + "VALUES (?,?,?,?,?,?,'RUNNING',?,?,?,clock_timestamp()+INTERVAL '90 seconds',clock_timestamp()+INTERVAL '10 minutes','UNKNOWN')",
                runId, taskId, identity.appId(), identity.credentialId(), requestId, attempt, (long) attempt, token, AnalysisEvidence.digest(token));
        jdbc.update("UPDATE crash_analysis_task SET state='RUNNING',current_run_id=? WHERE app_id=? AND task_id=?", runId, identity.appId(), taskId);
        return claimed(identity, runId);
    }

    /** 心跳只能延长当前租约，不能越过固定总运行期限或取消意图。 */
    public RunView heartbeat(WorkerCredentialService.Identity identity, UUID runId, Lease lease) {
        // 过期处理必须提交，即使随后返回租约失效错误。
        RunView result = transaction.execute(status -> {
            lockApp(identity.appId());
            lockCredential(identity);
            // 根据固定时间将失联结果转失败，并保持停止未确认。
            RunView run = view(identity, runId);
            expire(run);
            run = view(identity, runId);
            if (!"RUNNING".equals(run.state())) return run;
            validateLease(identity, run, lease);
            if (!enabled) {
                // 功能关闭必须返回可识别的停止意图，不能继续续租。
                jdbc.update("UPDATE crash_analysis_task SET state='CANCELLING' WHERE app_id=? AND task_id=? AND current_run_id=? AND state='RUNNING'", identity.appId(), run.taskId(), runId);
                return view(identity, runId);
            }
            if ("CANCELLING".equals(run.taskState())) return run;
            jdbc.update("UPDATE crash_analysis_run SET lease_expires_at=LEAST(clock_timestamp()+INTERVAL '90 seconds',deadline_at) WHERE run_id=? AND app_id=?", runId, identity.appId());
            return view(identity, runId);
        });
        if (!"RUNNING".equals(result.state())) throw conflict("ANALYSIS_LEASE_INVALID", "租约已失效");
        return result;
    }

    /** 只有当前有效分配能读取冻结证据，返回原始发送字节。 */
    public String evidence(WorkerCredentialService.Identity identity, UUID runId, Lease lease) {
        // 将到期状态与正文分开返回，在提交期限变化后才拒绝访问。
        ContentAttempt result = transaction.execute(status -> {
            lockApp(identity.appId());
            lockCredential(identity);
            // 不允许旧 Run 获取材料或越过取消状态。
            RunView run = view(identity, runId);
            expire(run);
            run = view(identity, runId);
            if (!"RUNNING".equals(run.state())) return new ContentAttempt(run, null);
            validateLease(identity, run, lease);
            if (!enabled || !"RUNNING".equals(run.taskState())) throw conflict("ANALYSIS_STOP_REQUIRED", "当前执行需停止");
            return new ContentAttempt(run, jdbc.queryForObject("SELECT evidence_text FROM crash_analysis_task WHERE app_id=? AND task_id=? AND current_run_id=?", String.class, identity.appId(), run.taskId(), runId));
        });
        if (result.content() == null) throw conflict("ANALYSIS_LEASE_INVALID", "租约已失效");
        return result.content();
    }

    /** 仅用于提交到期状态后的内部正文结果。 */
    private record ContentAttempt(/** 完整非敏感状态。 */ RunView run, /** 成功正文，可空。 */ String content) {}

    /** 查询原分配用于响应未知时对账，不返回秘密或其他 Worker 数据。 */
    @Transactional
    public RunView status(WorkerCredentialService.Identity identity, UUID runId) {
        lockApp(identity.appId());
        lockCredential(identity);
        // 状态读取同样识别过期，不假设进程停止。
        RunView run = view(identity, runId);
        expire(run);
        return view(identity, runId);
    }

    /** 完成回执携带材料工具已关闭的严格结果，宿主停止另行核验。 */
    public record Completion(/** 原分配租约。 */ Lease lease, /** 结构化结果的原始 JSON 字节文本。 */ String resultJson,
            /** 原文 UTF-8 摘要。 */ String resultSha256, /** 整体停止事实；宿主不允许自行声称 true。 */ boolean stopConfirmed,
            /** 提交前已关闭本地材料工具。 */ boolean localToolsStopped) {}

    /** 成功响应丢失后相同摘要返回原确认，首次结果必须在当前有效租约内。 */
    public RunView complete(WorkerCredentialService.Identity identity, UUID runId, Completion completion) {
        // 完整正文的字节上限在解析前检查，避免先加载任意大小结构。
        if (completion == null || completion.resultJson() == null || completion.resultJson().getBytes(StandardCharsets.UTF_8).length>1048576
                || !AnalysisEvidence.digest(completion.resultJson()).equals(completion.resultSha256()) || !completion.localToolsStopped() || completion.stopConfirmed()) {
            throw new QueryValidationException("INVALID_ANALYSIS_RESULT", "结果大小、摘要或停止事实无效", 400);
        }
        // 请求解析为严格 Schema，不把供应商的任意 JSON 当有效结果。
        AnalysisResult result;
        try {
            result = mapper.readerFor(AnalysisResult.class).with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).readValue(completion.resultJson());
        } catch (RuntimeException failure) {
            throw new QueryValidationException("INVALID_ANALYSIS_RESULT", "结果结构无效", 400);
        }
        if (result == null || !validator.validate(result).isEmpty()) throw new QueryValidationException("INVALID_ANALYSIS_RESULT", "结果字段约束无效", 400);
        // 原始 JSONB 投影大小也独立检查，不让格式空格绕过数据库上限。
        if (jdbc.queryForObject("SELECT octet_length((?::jsonb)::text)", Integer.class, completion.resultJson())>1048576) {
            throw new QueryValidationException("INVALID_ANALYSIS_RESULT", "结果投影超过上限", 400);
        }
        // 先提交过期结果拒绝所对应的状态，再向调用方返回失效错误。
        RunView finished = transaction.execute(status -> {
            lockApp(identity.appId());
            lockCredential(identity);
            // 完成重试不要求已结束租约仍有效，但必须属于原分配。
            RunView run = view(identity, runId);
            validateOriginalLease(identity, run, completion.lease());
            String saved = jdbc.queryForObject("SELECT result_sha256 FROM crash_analysis_run WHERE app_id=? AND run_id=?", String.class, identity.appId(), runId);
            if (saved != null) {
                if (!saved.equals(completion.resultSha256())) throw conflict("ANALYSIS_RESULT_CONFLICT", "本 Run 已保存不同结果");
                return run;
            }
            expire(run);
            run = view(identity, runId);
            if (!"RUNNING".equals(run.state())) return run;
            validateLease(identity, run, completion.lease());
            if (!enabled || !"RUNNING".equals(run.taskState())) throw conflict("ANALYSIS_STOP_REQUIRED", "取消或关闭后不接受成功结果");
            validateReferences(identity.appId(), run, result);
            jdbc.update("UPDATE crash_analysis_run SET state='SUCCEEDED',result=?::jsonb,result_text=?,result_sha256=?,local_tools_stopped=true,host_stop_state='UNKNOWN',stop_confirmed=false,finished_at=clock_timestamp(),lease_token=NULL WHERE app_id=? AND run_id=?",
                    completion.resultJson(), completion.resultJson(), completion.resultSha256(), identity.appId(), runId);
            jdbc.update("UPDATE crash_analysis_task SET state='SUCCEEDED',terminal_at=clock_timestamp() WHERE app_id=? AND task_id=? AND current_run_id=?", identity.appId(), run.taskId(), runId);
            return view(identity, runId);
        });
        if (!"SUCCEEDED".equals(finished.state())) throw conflict("ANALYSIS_LEASE_INVALID", "租约失效后不接受首次结果");
        return finished;
    }

    /** 后端核对证据归属与引用边界；源码片段由宿主报告，仅由 Worker 做提交时当前匹配检查。 */
    private void validateReferences(UUID appId, RunView run, AnalysisResult result) {
        // 结果绑定本次 Run，不将历史快照解释为直接读取模式。
        UUID taskId = run.taskId();
        // 本任务的固定证据作为唯一引用来源。
        String evidence = jdbc.queryForObject("SELECT evidence_text FROM crash_analysis_task WHERE app_id=? AND task_id=?", String.class, appId, taskId);
        // 证据是本服务生成且已经通过大小约束的 JSON。
        var frozen = mapper.readTree(evidence);
        if (!frozen.path("evidenceId").asText().equals(result.evidenceId().toString())
                || !result.runId().equals(run.runId()) || run.snapshotId() != null || frozen.path("schemaVersion").asInt()!=2) throw conflict("ANALYSIS_REFERENCE_INVALID", "结果引用不属于当前任务");
        // 固定片段集合不会接收跨任务 ID。
        Set<String> fragments = new java.util.HashSet<>();
        frozen.path("fragments").forEach(fragment -> fragments.add(fragment.path("id").asText()));
        for (var candidate : result.candidates()) {
            if (!fragments.containsAll(candidate.evidenceRefs())) throw conflict("ANALYSIS_REFERENCE_INVALID", "证据片段不存在");
        }
        if (("ROOT_CAUSE_CANDIDATE".equals(result.conclusion()) && result.candidates().isEmpty())
                || ("INSUFFICIENT_EVIDENCE".equals(result.conclusion()) && result.unknowns().isEmpty())) throw conflict("ANALYSIS_REFERENCE_INVALID", "结论缺少对应候选或未知项");
        for (var source : result.sourceRefs()) {
            // 相对路径白名单不允许目录穿越、Windows 驱动器或 Git 元数据。
            if (source.path().startsWith("/") || source.path().contains("\\") || source.path().contains(":") || source.path().indexOf(0)>=0
                    || java.util.Arrays.stream(source.path().split("/", -1)).anyMatch(part -> part.isEmpty() || part.equals(".") || part.equals("..") || part.equals(".git"))
                    || source.endLine()<source.startLine() || (long)source.endLine()-source.startLine()>=200
                    || !validPath(source.path())
                    || source.snippet().getBytes(StandardCharsets.UTF_8).length>8192
                    || source.snippet().split("\n", -1).length!=(long)source.endLine()-source.startLine()+1
                    || !AnalysisEvidence.digest(source.snippet()).equals(source.snippetSha256())) throw conflict("ANALYSIS_REFERENCE_INVALID", "源码引用边界或摘要无效");
        }
        if (result.schemaVersion()!=4
                || ("HOST_REPORTED".equals(result.execution().metadataSource()) && (result.execution().host()==null || result.execution().host().isBlank()))
                || ("UNKNOWN".equals(result.execution().metadataSource()) && (result.execution().host()!=null || result.execution().hostVersion()!=null))) {
            throw conflict("ANALYSIS_CONFIG_MISMATCH", "实际执行配置与任务不一致");
        }
        // 修改事实与自报测试分开校验，不能以成功保存报告伪造验证通过。
        for (var file : result.repair().files()) {
            if (!validPath(file.path())) throw conflict("ANALYSIS_REFERENCE_INVALID", "修改路径无效");
        }
        if (result.repair().files().stream().map(AnalysisResult.Edit::path).distinct().count()!=result.repair().files().size()
                || (List.of("NOT_REQUESTED","NOT_APPLICABLE").contains(result.repair().status()) && !result.repair().files().isEmpty())
                || (List.of("APPLIED","PARTIAL").contains(result.repair().status()) && result.repair().files().isEmpty())
                || ("NOT_RUN".equals(result.verification().status()) && (!result.verification().commands().isEmpty() || result.verification().reason().isBlank()))
                || ("FAILED".equals(result.verification().status()) && result.verification().commands().stream().noneMatch(command -> command.exitCode()!=0))
                || ("PASSED".equals(result.verification().status()) && (result.verification().commands().isEmpty() || result.verification().commands().stream().anyMatch(command -> command.exitCode()!=0)))) {
            throw conflict("ANALYSIS_REFERENCE_INVALID", "修改或验证事实不一致");
        }
    }

    /** 项目相对路径白名单；不能通过自报引用回传工具、秘密或构建附件。 */
    private boolean validPath(String path) {
        // 路径保持普通相对形式，不规范化危险输入。
        if (path.isBlank() || path.startsWith("/") || path.contains("\\") || path.contains(":") || path.indexOf(0)>=0) return false;
        // 工具配置与本地运行环境不属于公开报告源码。
        Set<String> excluded = Set.of(".git", ".agents", ".codex", ".venv", "venv", "__pycache__", "node_modules", "build", "dist", "target", ".gradle", ".idea", "agent-skills", "agent-worker");
        // 不允许空段、目录穿越或元数据目录。
        String[] parts = path.split("/", -1);
        if (java.util.Arrays.stream(parts).anyMatch(part -> part.isEmpty() || part.equals(".") || part.equals("..") || excluded.contains(part))) return false;
        // 秘密配置和签名文件只按名称拒绝，不读取其内容。
        String name = parts[parts.length-1].toLowerCase(java.util.Locale.ROOT);
        if (name.startsWith(".env") || Set.of("local.properties", "credentials.json", "repositories.json", "config.local.json").contains(name)) return false;
        // 二进制附件与签名不能作为文本源码引用或修改事实。
        Set<String> suffixes = Set.of("pem", "key", "p12", "pfx", "jks", "keystore", "aar", "jar", "apk", "aab", "dex", "class", "so", "dll", "dylib", "o", "a", "hprof", "zip", "gz", "7z", "rar", "tar", "bz2", "xz", "png", "jpg", "jpeg", "gif", "webp", "ico", "pdf", "mp4", "mp3", "woff", "woff2", "ttf", "otf");
        // 仅检查最后扩展名，普通无扩展源码仍可引用。
        int dot = name.lastIndexOf('.');
        return dot<0 || !suffixes.contains(name.substring(dot+1));
    }

    /** 独立稳定终止原因，不接收错误堆栈或任意日志。 */
    private static final Set<String> FAILURE_CODES = Set.of("CANCELLED", "LEASE_EXPIRED", "EXECUTOR_FAILED", "CONFIG_MISMATCH",
            "SOURCE_MISSING", "REQUEST_LIMIT", "INPUT_LIMIT", "TIME_LIMIT", "FORMAT_INVALID", "REFERENCE_INVALID", "NETWORK_ERROR");

    /** 执行失败与停止事实分离，清理失败不能声称旧环境已终止。 */
    public record Failure(/** 原分配租约。 */ Lease lease, /** 有限稳定码。 */ String errorCode,
            /** 不允许宿主自报整体停止。 */ boolean stopConfirmed,
            /** 本地工具关闭事实。 */ boolean localToolsStopped) {}

    /** 当前运行可报告失败；未确认停止时仍阻止任何新尝试。 */
    public RunView fail(WorkerCredentialService.Identity identity, UUID runId, Failure failure) {
        if (failure == null || failure.errorCode() == null || !FAILURE_CODES.contains(failure.errorCode())) throw new QueryValidationException("INVALID_ANALYSIS_FAILURE", "终止原因无效", 400);
        // 停止确认走支持到期租约的原分配校验，不重复修改终态。
        if (failure.stopConfirmed()) throw conflict("ANALYSIS_STOP_UNCONFIRMED", "宿主停止须实际核验，不能由 Worker 自报");
        if (failure.localToolsStopped()) return transaction.execute(status -> stopped(identity, runId, failure.lease(), failure.errorCode()));
        // 到期变化提交后再报告租约失效，错误原文不进入后端。
        RunView result = transaction.execute(status -> {
            lockApp(identity.appId());
            lockCredential(identity);
            // 重复同原因失败可对账，不能更正成其他错误或成功。
            RunView run = view(identity, runId);
            validateOriginalLease(identity, run, failure.lease());
            if ("FAILED".equals(run.state()) && failure.errorCode().equals(run.errorCode())) return run;
            expire(run);
            run = view(identity, runId);
            if (!"RUNNING".equals(run.state())) return run;
            validateLease(identity, run, failure.lease());
            jdbc.update("UPDATE crash_analysis_run SET state='FAILED',error_code=?,finished_at=clock_timestamp(),lease_token=NULL WHERE app_id=? AND run_id=?", failure.errorCode(), identity.appId(), runId);
            jdbc.update("UPDATE crash_analysis_task SET state='FAILED',terminal_at=clock_timestamp() WHERE app_id=? AND task_id=? AND current_run_id=? AND state IN ('RUNNING','CANCELLING')", identity.appId(), run.taskId(), runId);
            return view(identity, runId);
        });
        if (!"FAILED".equals(result.state()) || !failure.errorCode().equals(result.errorCode())) throw conflict("ANALYSIS_STATE_CONFLICT", "不能覆盖当前执行终态");
        return result;
    }

    /** Worker 仅回执本地材料工具关闭，宿主停止仍待核验，允许租约已过期但不得跨身份。 */
    @Transactional
    public RunView stopped(WorkerCredentialService.Identity identity, UUID runId, Lease lease, String errorCode) {
        lockApp(identity.appId());
        lockCredential(identity);
        // 停止是独立事实；不因租约到期拒绝原分配的停止回执。
        RunView run = view(identity, runId);
        validateOriginalLease(identity, run, lease);
        if (errorCode == null || !FAILURE_CODES.contains(errorCode)) throw new QueryValidationException("INVALID_ANALYSIS_FAILURE", "终止原因无效", 400);
        if (run.stopConfirmed() || run.localToolsStopped()) return run;
        // 已经失效的旧 Run 只能确认停止，不能改写更新任务或已有成功。
        boolean cancelling = "CANCELLING".equals(run.taskState());
        String terminal = cancelling || "CANCELLED".equals(errorCode) ? "CANCELLED" : "FAILED";
        jdbc.update("UPDATE crash_analysis_run SET local_tools_stopped=true,host_stop_state='UNKNOWN',state=CASE WHEN state='RUNNING' THEN ? ELSE state END,error_code=COALESCE(error_code,?),finished_at=COALESCE(finished_at,clock_timestamp()),lease_token=NULL WHERE app_id=? AND run_id=?",
                terminal, errorCode, identity.appId(), runId);
        jdbc.update("UPDATE crash_analysis_task SET state=?,terminal_at=clock_timestamp() WHERE app_id=? AND task_id=? AND current_run_id=? AND state IN ('RUNNING','CANCELLING')", terminal, identity.appId(), run.taskId(), runId);
        return view(identity, runId);
    }

    /** Worker 撤销或失联时，管理员可核验明确环境已停止并留审计依据。 */
    @Transactional
    public void confirmStopped(UUID appId, UUID runId, Authentication authentication, String basis) {
        access.requireEdit(appId, authentication);
        lockApp(appId);
        // 人工记录不可空，不能用模型结论替代宿主本次分析和本地材料的实际核验。
        String normalized = basis == null ? "" : basis.strip();
        if (normalized.isEmpty() || normalized.length() > 2000) throw new QueryValidationException("INVALID_STOP_BASIS", "停止核验依据需为 1 到 2000 字符", 400);
        List<UUID> tasks = jdbc.queryForList("SELECT task_id FROM crash_analysis_run WHERE app_id=? AND run_id=? FOR UPDATE", UUID.class, appId, runId);
        if (tasks.isEmpty()) throw notFound();
        // 已确认的停止审计保持原事实，管理员重复核验不混改来源。
        if (Boolean.TRUE.equals(jdbc.queryForObject("SELECT stop_confirmed FROM crash_analysis_run WHERE app_id=? AND run_id=?", Boolean.class, appId, runId))) return;
        // 有效运行必须先发出取消意图，不能将仍在执行的环境标为停止。
        Integer active = jdbc.queryForObject("SELECT count(*) FROM crash_analysis_run r JOIN crash_analysis_task t ON t.current_run_id=r.run_id WHERE r.app_id=? AND r.run_id=? AND r.state='RUNNING' AND t.state<>'CANCELLING' AND r.lease_expires_at>clock_timestamp() AND r.deadline_at>clock_timestamp()", Integer.class, appId, runId);
        if (active > 0) throw conflict("ANALYSIS_STATE_CONFLICT", "请先取消活动任务并核验环境停止");
        jdbc.update("UPDATE crash_analysis_run SET local_tools_stopped=true,host_stop_state=CASE WHEN host_stop_state='NOT_APPLICABLE' THEN host_stop_state ELSE 'CONFIRMED' END,stop_confirmed=true,stopped_at=COALESCE(stopped_at,clock_timestamp()),stopped_by=COALESCE(stopped_by,?),stop_basis=COALESCE(stop_basis,?),state=CASE WHEN state='RUNNING' THEN 'CANCELLED' ELSE state END,finished_at=COALESCE(finished_at,clock_timestamp()),lease_token=NULL WHERE app_id=? AND run_id=?", access.requireUserId(authentication), normalized, appId, runId);
        jdbc.update("UPDATE crash_analysis_task SET state='CANCELLED',terminal_at=clock_timestamp() WHERE app_id=? AND task_id=? AND current_run_id=? AND state IN ('RUNNING','CANCELLING')", appId, tasks.getFirst(), runId);
    }

    /** 每次执行的公开历史，结果过期保持明确标记。 */
    public record RunDetail(/** 执行摘要。 */ RunView run, /** 正常结果可空。 */ tools.jackson.databind.JsonNode result,
            /** 任务内容到期状态。 */ boolean contentExpired) {}

    /** 成员查看有界 Run 历史，凭据和租约秘密绝不进入网页。 */
    public List<RunDetail> history(UUID appId, UUID taskId, Authentication authentication, int page, int size) {
        access.requireView(appId, authentication);
        if (page<0 || page>1000 || size<1 || size>100) throw new QueryValidationException("INVALID_ANALYSIS_PAGE", "分页参数无效", 400);
        if (jdbc.queryForObject("SELECT count(*) FROM crash_analysis_task WHERE app_id=? AND task_id=?", Integer.class, appId, taskId)!=1) throw notFound();
        return jdbc.query("SELECT r.*,t.state AS task_state,t.content_expired,clock_timestamp() AS server_now FROM crash_analysis_run r JOIN crash_analysis_task t ON t.task_id=r.task_id AND t.app_id=r.app_id WHERE r.app_id=? AND r.task_id=? ORDER BY r.attempt DESC LIMIT ? OFFSET ?",
                (row, number) -> new RunDetail(new RunView(row.getObject("run_id", UUID.class), row.getObject("task_id", UUID.class), row.getInt("attempt"), row.getString("state"), row.getString("task_state"), row.getLong("lease_generation"),
                        row.getTimestamp("lease_expires_at").toInstant(), row.getTimestamp("deadline_at").toInstant(), row.getTimestamp("server_now").toInstant(), row.getBoolean("stop_confirmed"), row.getString("error_code"), row.getBoolean("local_tools_stopped"), row.getString("host_stop_state"), row.getObject("snapshot_id", UUID.class)),
                        row.getString("result_text")==null ? null : mapper.readTree(row.getString("result_text")), row.getBoolean("content_expired")), appId, taskId, size, (long)page*size);
    }

    /** 原始分配身份校验允许租约失效，仅用于停止或已完成结果的幂等确认。 */
    private void validateOriginalLease(WorkerCredentialService.Identity identity, RunView run, Lease lease) {
        if (lease == null || lease.token() == null || lease.token().length()!=43 || run.leaseGeneration()!=lease.generation()) throw conflict("ANALYSIS_LEASE_INVALID", "原分配身份无效");
        Integer matches = jdbc.queryForObject("SELECT count(*) FROM crash_analysis_run WHERE app_id=? AND run_id=? AND credential_id=? AND lease_sha256=?", Integer.class,
                identity.appId(), run.runId(), identity.credentialId(), AnalysisEvidence.digest(lease.token()));
        if (matches!=1) throw conflict("ANALYSIS_LEASE_INVALID", "原分配身份无效");
    }

    /** 事务内重新锁定有效凭据，避免过滤器认证和实际写入之间被撤销。 */
    private void lockCredential(WorkerCredentialService.Identity identity) {
        List<UUID> valid = jdbc.queryForList("SELECT credential_id FROM analysis_worker_credential WHERE credential_id=? AND app_id=? AND revoked_at IS NULL AND expires_at>clock_timestamp() FOR UPDATE",
                UUID.class, identity.credentialId(), identity.appId());
        if (valid.isEmpty()) throw new QueryValidationException("WORKER_CREDENTIAL_INVALID", "Worker 凭据无效", 401);
    }

    /** 各生命周期变化与任务创建/构建更正共用应用锁。 */
    private void lockApp(UUID appId) { jdbc.query("SELECT pg_advisory_xact_lock(hashtextextended(?,0))", row -> {}, appId.toString()); }

    /** 非敏感当前 Run 及所属任务投影。 */
    private RunView view(WorkerCredentialService.Identity identity, UUID runId) {
        List<RunView> rows = jdbc.query("SELECT r.*,t.state AS task_state,clock_timestamp() AS server_now FROM crash_analysis_run r JOIN crash_analysis_task t ON t.task_id=r.task_id AND t.app_id=r.app_id WHERE r.app_id=? AND r.run_id=? AND r.credential_id=?",
                (row, number) -> new RunView(row.getObject("run_id", UUID.class), row.getObject("task_id", UUID.class), row.getInt("attempt"), row.getString("state"), row.getString("task_state"), row.getLong("lease_generation"),
                        row.getTimestamp("lease_expires_at").toInstant(), row.getTimestamp("deadline_at").toInstant(), row.getTimestamp("server_now").toInstant(), row.getBoolean("stop_confirmed"), row.getString("error_code"), row.getBoolean("local_tools_stopped"), row.getString("host_stop_state"), row.getObject("snapshot_id", UUID.class)), identity.appId(), runId, identity.credentialId());
        if (rows.isEmpty()) throw notFound();
        return rows.getFirst();
    }

    /** 领取响应仅在当前活动分配返回原租约；已结束不重新授权执行。 */
    private Claimed claimed(WorkerCredentialService.Identity identity, UUID runId) {
        return new Claimed(view(identity, runId), jdbc.queryForObject("SELECT lease_token FROM crash_analysis_run WHERE app_id=? AND run_id=? AND credential_id=?", String.class, identity.appId(), runId, identity.credentialId()));
    }

    /** 到期变失败但停止仍未知，不让旧 Run 的结果覆盖当前任务。 */
    private void expire(RunView run) {
        if ("RUNNING".equals(run.state()) && (!run.leaseExpiresAt().isAfter(run.serverNow()) || !run.deadlineAt().isAfter(run.serverNow()))) {
            // 总运行期限与失联租约使用不同稳定原因。
            String reason = !run.deadlineAt().isAfter(run.serverNow()) ? "TIME_LIMIT" : "LEASE_EXPIRED";
            jdbc.update("UPDATE crash_analysis_run SET state='FAILED',error_code=?,finished_at=clock_timestamp(),lease_token=NULL WHERE run_id=? AND state='RUNNING'", reason, run.runId());
            jdbc.update("UPDATE crash_analysis_task SET state='FAILED',terminal_at=clock_timestamp() WHERE task_id=? AND current_run_id=? AND state IN ('RUNNING','CANCELLING')", run.taskId(), run.runId());
        }
    }

    /** 验证分配代次、秘密及当前任务拥有权，停止回执将使用单独更宽的身份核验。 */
    private void validateLease(WorkerCredentialService.Identity identity, RunView run, Lease lease) {
        if (lease == null || lease.token() == null || lease.token().length() != 43 || run.leaseGeneration() != lease.generation()
                || !"RUNNING".equals(run.state()) || !run.leaseExpiresAt().isAfter(run.serverNow())) throw conflict("ANALYSIS_LEASE_INVALID", "租约已失效");
        // 摘要在当前凭据所属 Run 内比较，不接受其他历史 Run。
        Integer current = jdbc.queryForObject("SELECT count(*) FROM crash_analysis_run r JOIN crash_analysis_task t ON t.current_run_id=r.run_id WHERE r.app_id=? AND r.run_id=? AND r.credential_id=? AND r.lease_sha256=?",
                Integer.class, identity.appId(), run.runId(), identity.credentialId(), AnalysisEvidence.digest(lease.token()));
        if (current != 1) throw conflict("ANALYSIS_LEASE_INVALID", "租约已失效");
    }

    /** 未开启不能分配新运行。 */
    private void requireEnabled() { if (!enabled) throw new QueryValidationException("ANALYSIS_DISABLED", "分析功能未开启", 503); }
    /** 不区分不存在与其他身份对象。 */
    private QueryValidationException notFound() { return new QueryValidationException("ANALYSIS_RUN_NOT_FOUND", "执行对象不存在", 404); }
    /** 返回稳定生命周期冲突。 */
    private QueryValidationException conflict(String code, String message) { return new QueryValidationException(code, message, 409); }
}
