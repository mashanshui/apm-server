package com.shanshui.apmserver.symbol.internal.persistence;

import com.shanshui.apmserver.symbol.api.SymbolBuildIndex;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

/** 活动符号表索引的有界元数据查询。 */
@Repository
public class SymbolBuildIndexRepository implements SymbolBuildIndex {
    /** 与符号表管理事务共用的数据源。 */
    private final JdbcTemplate jdbc;
    /** 注入管理库，不依赖 Crash 内部表或实现。 */
    public SymbolBuildIndexRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    /** 唯一身份游标避免使用偏移扫描或在 JVM 收集所有应用。 */
    @Override
    public List<Build> activeAfter(UUID afterSymbolId, int limit) {
        if (limit < 1 || limit > 100) throw new IllegalArgumentException("版本对账批次超限");
        return jdbc.query("SELECT symbol_id,app_id,app_version FROM app_symbol_file WHERE deleted_at IS NULL AND symbol_type='MAPPING' AND app_version IS NOT NULL AND (?::uuid IS NULL OR symbol_id>?::uuid) ORDER BY symbol_id LIMIT ?",
                (row, number) -> new Build(row.getObject("symbol_id", UUID.class), row.getObject("app_id", UUID.class), row.getString("app_version")),
                afterSymbolId, afterSymbolId, limit);
    }
}
