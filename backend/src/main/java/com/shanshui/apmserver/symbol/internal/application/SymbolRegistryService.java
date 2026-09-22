package com.shanshui.apmserver.symbol.internal.application;

import com.shanshui.apmserver.symbol.api.SymbolFileLease;
import com.shanshui.apmserver.symbol.api.SymbolRegistry;
import com.shanshui.apmserver.symbol.api.SymbolicationResult;
import com.shanshui.apmserver.symbol.internal.config.SymbolProperties;
import com.shanshui.apmserver.symbol.internal.domain.SymbolFileEntity;
import com.shanshui.apmserver.symbol.internal.retrace.R8RetraceEngine;
import com.shanshui.apmserver.symbol.internal.storage.SymbolFileStore;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** 供 Crash 和卡顿模块使用的统一符号表注册表实现。 */
@Service
public class SymbolRegistryService implements SymbolRegistry {

    /** 当前版本查询服务。 */
    private final SymbolManagementService managementService;
    /** 不可变文件租约存储。 */
    private final SymbolFileStore fileStore;
    /** 官方 R8 还原引擎。 */
    private final R8RetraceEngine retraceEngine;
    /** 上传校验和请求还原共享的有界许可。 */
    private final SymbolOperationLimiter operationLimiter;
    /** 单次还原输出上限。 */
    private final long maxOutputBytes;

    /** 初始化共享资源许可。 */
    public SymbolRegistryService(SymbolManagementService managementService,
                                 SymbolFileStore fileStore,
                                 R8RetraceEngine retraceEngine,
                                 SymbolProperties properties,
                                 SymbolOperationLimiter operationLimiter) {
        this.managementService = managementService;
        this.fileStore = fileStore;
        this.retraceEngine = retraceEngine;
        this.operationLimiter = operationLimiter;
        this.maxOutputBytes = properties.getMaxOutputBytes();
    }

    /** 应用启动后清理数据库未引用的内部文件。 */
    @EventListener(ApplicationReadyEvent.class)
    public void cleanOrphansOnStartup() {
        managementService.cleanupOrphans();
    }

    /** 根据应用和构建标识获取固定版本文件租约。 */
    @Override
    public Optional<SymbolFileLease> acquire(UUID appId, String buildId) {
        SymbolFileEntity entity = managementService.current(appId, buildId);
        return entity == null ? Optional.empty() : Optional.of(fileStore.open(entity));
    }

    /** 在共享许可内执行一次完整异常链还原。 */
    @Override
    public SymbolicationResult retrace(SymbolFileLease lease, List<String> stackLines) {
        try (SymbolOperationLimiter.Permit ignored = operationLimiter.acquire()) {
            return retraceEngine.retrace(lease.path(), stackLines, maxOutputBytes);
        } catch (com.shanshui.apmserver.symbol.api.SymbolParserBusyException ex) {
            return SymbolicationResult.busy();
        }
    }

    /** 关闭一个租约，不让 API 调用方接触内部存储实现。 */
    public void closeQuietly(SymbolFileLease lease) {
        if (lease == null) {
            return;
        }
        try {
            lease.close();
        } catch (IOException ignored) {
            // 读取租约只维护进程内计数，关闭失败不向用户暴露内部路径。
        }
    }
}
