package com.shanshui.apmserver.symbol.internal.persistence;

import com.shanshui.apmserver.symbol.internal.domain.SymbolAuditEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

/** 符号表替换审计的数据库访问接口。 */
public interface SymbolAuditRepository extends JpaRepository<SymbolAuditEntity, UUID> {
}
