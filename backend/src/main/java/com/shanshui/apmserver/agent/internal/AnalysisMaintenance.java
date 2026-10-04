package com.shanshui.apmserver.agent.internal;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.UUID;

/** 有界数据库维护，期限失败与内容到期不意味着本地环境已停止。 */
@Service
public class AnalysisMaintenance {
    /** 只操作新增管理表。 */
    private final JdbcTemplate jdbc;
    /** 每个任务一份短事务，与领取和重试共用应用锁。 */
    private final TransactionTemplate transaction;
    /** 功能关闭时向活动任务发送停止意图。 */
    private final boolean enabled;

    /** 注入数据库及当前部署开关。 */
    public AnalysisMaintenance(JdbcTemplate jdbc, PlatformTransactionManager manager,
            @Value("${apm.agent.analysis.enabled:false}") boolean enabled) {
        this.jdbc = jdbc;
        this.transaction = new TransactionTemplate(manager);
        this.enabled = enabled;
    }

    /** 扫描量每次最多 100，服务启动后延迟避免与迁移抢占。 */
    @Scheduled(fixedDelayString="${apm.agent.analysis.maintenance-interval-ms:10000}", initialDelayString="${apm.agent.analysis.maintenance-initial-delay-ms:60000}")
    public void sweep() {
        if (!enabled) stopDisabled();
        // 同时识别租约到期、总时间上限及执行凭据失效；不加载业务 Crash 数据。
        List<UUID> expired = jdbc.queryForList("SELECT r.run_id FROM crash_analysis_run r JOIN analysis_worker_credential c ON c.credential_id=r.credential_id WHERE r.state='RUNNING' AND (r.lease_expires_at<=clock_timestamp() OR r.deadline_at<=clock_timestamp() OR c.revoked_at IS NOT NULL OR c.expires_at<=clock_timestamp()) ORDER BY r.lease_expires_at,r.run_id LIMIT 100", UUID.class);
        for (UUID runId : expired) {
            // 仅从本次最多百条候选读取应用锁标识。
            UUID appId = jdbc.queryForObject("SELECT app_id FROM crash_analysis_run WHERE run_id=?", UUID.class, runId);
            transaction.executeWithoutResult(status -> {
                lock(appId);
                // 在事务内重新检查，避免并发心跳已续租仍被过期处理。
                int changed = jdbc.update("UPDATE crash_analysis_run r SET state='FAILED',error_code=CASE WHEN r.deadline_at<=clock_timestamp() THEN 'TIME_LIMIT' WHEN EXISTS(SELECT 1 FROM analysis_worker_credential c WHERE c.credential_id=r.credential_id AND (c.revoked_at IS NOT NULL OR c.expires_at<=clock_timestamp())) THEN 'WORKER_CREDENTIAL_INVALID' ELSE 'LEASE_EXPIRED' END,finished_at=clock_timestamp(),lease_token=NULL WHERE r.app_id=? AND r.run_id=? AND r.state='RUNNING' AND (r.lease_expires_at<=clock_timestamp() OR r.deadline_at<=clock_timestamp() OR EXISTS(SELECT 1 FROM analysis_worker_credential c WHERE c.credential_id=r.credential_id AND (c.revoked_at IS NOT NULL OR c.expires_at<=clock_timestamp())))", appId, runId);
                if (changed==1) jdbc.update("UPDATE crash_analysis_task SET state='FAILED',terminal_at=clock_timestamp() WHERE app_id=? AND current_run_id=? AND state IN ('RUNNING','CANCELLING')", appId, runId);
            });
        }
        expireContents();
    }

    /** 功能关闭后取消待启动任务，已运行的任务等待 Worker 停止确认。 */
    private void stopDisabled() {
        // 每批有限任务避免停用时产生大事务。
        List<UUID> ids = jdbc.queryForList("SELECT task_id FROM crash_analysis_task WHERE state IN ('BLOCKED','READY','RUNNING') ORDER BY created_at,task_id LIMIT 100", UUID.class);
        for (UUID id : ids) {
            // 此处仅使用平台生成的任务标识，不接受调用方应用扩权。
            UUID appId = jdbc.queryForObject("SELECT app_id FROM crash_analysis_task WHERE task_id=?", UUID.class, id);
            transaction.executeWithoutResult(status -> {
                lock(appId);
                jdbc.update("UPDATE crash_analysis_task SET state=CASE WHEN state='RUNNING' THEN 'CANCELLING' ELSE 'CANCELLED' END,terminal_at=CASE WHEN state='RUNNING' THEN NULL ELSE clock_timestamp() END,preparation_generation=preparation_generation+1,block_reason=NULL WHERE app_id=? AND task_id=? AND state IN ('BLOCKED','READY','RUNNING')", appId, id);
            });
        }
    }

    /** 终态保留三十天正文，停止尚未确认的任何 Run 均不得清理。 */
    private void expireContents() {
        // 索引先按终态时间过滤，最多百条进入逐任务事务。
        List<UUID> ids = jdbc.queryForList("SELECT t.task_id FROM crash_analysis_task t WHERE t.content_expired=false AND t.state IN ('SUCCEEDED','FAILED','CANCELLED') AND t.terminal_at<clock_timestamp()-INTERVAL '30 days' AND NOT EXISTS(SELECT 1 FROM crash_analysis_run r WHERE r.task_id=t.task_id AND r.stop_confirmed=false) ORDER BY t.terminal_at,t.task_id LIMIT 100", UUID.class);
        for (UUID id : ids) {
            // 等待锁后必须重复状态条件，防止刚重试的活动证据被清理。
            UUID appId = jdbc.queryForObject("SELECT app_id FROM crash_analysis_task WHERE task_id=?", UUID.class, id);
            transaction.executeWithoutResult(status -> {
                lock(appId);
                int changed = jdbc.update("UPDATE crash_analysis_task t SET content_expired=true,evidence=NULL,evidence_text=NULL WHERE t.app_id=? AND t.task_id=? AND t.content_expired=false AND t.state IN ('SUCCEEDED','FAILED','CANCELLED') AND t.terminal_at<clock_timestamp()-INTERVAL '30 days' AND NOT EXISTS(SELECT 1 FROM crash_analysis_run r WHERE r.task_id=t.task_id AND r.stop_confirmed=false)", appId, id);
                if (changed==1) jdbc.update("UPDATE crash_analysis_run SET result=NULL,result_text=NULL,lease_token=NULL WHERE app_id=? AND task_id=?", appId, id);
            });
        }
    }

    /** 维护与用户写入按应用串行，锁在当前短事务结束时释放。 */
    private void lock(UUID appId) { jdbc.query("SELECT pg_advisory_xact_lock(hashtextextended(?,0))", row -> {}, appId.toString()); }
}
