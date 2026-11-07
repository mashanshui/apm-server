package com.shanshui.apmserver.crash.internal.application;

import com.shanshui.apmserver.crash.api.CrashSymbolicationWork;
import com.shanshui.apmserver.symbol.api.SymbolBuildIndex;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.UUID;

/** 补偿跨库通知失败与迟到事件，轮转检查点和本页唤醒原子提交。 */
@Service
public class CrashSymbolicationReconciler {
    /** 只通过 symbol 公开索引访问活动构建。 */
    private final SymbolBuildIndex symbols;
    /** 持久化构建工作。 */
    private final CrashSymbolicationWork jobs;
    /** 本模块轮转检查点。 */
    private final JdbcTemplate jdbc;
    /** 短事务共享管理数据源。 */
    private final TransactionTemplate transaction;

    /** 注入活动索引、公开唤醒边界和管理事务。 */
    public CrashSymbolicationReconciler(SymbolBuildIndex symbols, CrashSymbolicationWork jobs,
            JdbcTemplate jdbc, PlatformTransactionManager manager) {
        this.symbols = symbols;
        this.jobs = jobs;
        this.jdbc = jdbc;
        this.transaction = new TransactionTemplate(manager);
    }

    /** 每次最多轮转 100 个活动构建，不以事件发生时间推进永久游标。 */
    public void reconcilePage() {
        transaction.executeWithoutResult(status -> {
            // 检查点锁把多个调用串行化，唤醒失败时检查点一并回滚。
            UUID after = jdbc.queryForObject("SELECT after_symbol_id FROM crash_symbolication_reconcile WHERE singleton=true FOR UPDATE", UUID.class);
            var builds = symbols.activeAfter(after, 100);
            for (var build : builds) jobs.wake(build.appId(), build.buildId());
            jdbc.update("UPDATE crash_symbolication_reconcile SET after_symbol_id=? WHERE singleton=true",
                    builds.size() < 100 ? null : builds.getLast().symbolId());
        });
    }
}
