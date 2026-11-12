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
import java.time.Duration;

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
    /** 辅助线程专用单工作者，不增加共享解析许可。 */
    private final AuxiliaryRetraceExecutor auxiliary;

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
        this.auxiliary = new AuxiliaryRetraceExecutor(operationLimiter, retraceEngine::retrace);
    }

    /** 应用启动后清理数据库未引用的内部文件。 */
    @EventListener(ApplicationReadyEvent.class)
    public void cleanOrphansOnStartup() {
        managementService.cleanupOrphans();
    }

    /** 版本不存在时后台直接等待，不扫描遥测。 */
    @Override
    public boolean hasVersion(UUID appId, String appVersion) { return managementService.hasVersion(appId, appVersion); }

    /** 根据应用和构建标识获取固定版本文件租约。 */
    @Override
    public Optional<SymbolFileLease> acquire(UUID appId, String buildId) {
        return managementService.acquire(appId, buildId);
    }

    /** 获取指定应用版本下按构建优先选定的固定来源。 */
    @Override
    public Optional<SymbolFileLease> acquireVersioned(UUID appId, String appVersion, String buildId) {
        return managementService.acquireVersioned(appId, appVersion, buildId);
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

    /** 以同一固定来源执行辅助栈还原，调用方仅等待其剩余预算。 */
    @Override
    public SymbolicationResult retraceAuxiliary(SymbolFileLease lease, List<String> stackLines, long maxBytes, Duration wait) {
        return auxiliary.retrace(lease, stackLines, Math.min(maxBytes, Math.min(maxOutputBytes, 32 * 1024L)), wait);
    }

    /** 停止接收辅助任务，已运行的 R8 退出时再释放其引用。 */
    @jakarta.annotation.PreDestroy
    public void stopAuxiliary() { auxiliary.close(); }

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
