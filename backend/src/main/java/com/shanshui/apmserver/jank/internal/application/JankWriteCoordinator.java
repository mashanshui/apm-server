package com.shanshui.apmserver.jank.internal.application;

import com.shanshui.apmserver.jank.internal.domain.JankStoredSignal;
import com.shanshui.apmserver.jank.internal.port.JankEventRepository;
import com.shanshui.apmserver.telemetry.api.AppendResult;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Jank 写入唯一协调入口。具体适配器在一次调用内负责原始事件、事实、详情、
 * 重复检测以及缺失事实修复。
 */
@Service
public class JankWriteCoordinator {

    private final JankEventRepository repository;

    public JankWriteCoordinator(JankEventRepository repository) {
        this.repository = repository;
    }

    public AppendResult append(java.util.UUID appId, List<JankStoredSignal> events) {
        return repository.append(appId, List.copyOf(events));
    }
}
