package com.shanshui.apmserver.symbol.internal.application;

import com.shanshui.apmserver.symbol.api.AnalysisSymbolVersions;
import com.shanshui.apmserver.symbol.internal.persistence.SymbolFileRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/** 在证据发布的短事务中核对符号版本，不跨事务持有 parser 资源。 */
@Service
public class AnalysisSymbolVersionService implements AnalysisSymbolVersions {
    /** 符号域内部元数据查询器。 */
    private final SymbolFileRepository repository;

    /** 注入符号表当前版本仓库。 */
    public AnalysisSymbolVersionService(SymbolFileRepository repository) {
        this.repository = repository;
    }

    /** 必须加入调用方事务；锁随证据发布提交或回滚释放。 */
    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean lockAndMatch(UUID appId, String buildId, UUID symbolId, int revision, String sha256) {
        return repository.lockCurrentForAnalysis(appId, buildId)
                .filter(row -> row.getSymbolId().equals(symbolId) && row.getRevision() == revision
                        && row.getSha256().equals(sha256)).isPresent();
    }
}
