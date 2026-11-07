package com.shanshui.apmserver.crash.api;

import java.time.Instant;
import java.util.UUID;

/** 符号表管理与接收方共用的持久化构建唤醒边界。 */
public interface CrashSymbolicationWork {
    /** 必须与符号表元数据事务共同提交，不执行历史查询或 Retrace。 */
    void wake(UUID appId, String buildId);

    /** 只读一个构建的任务投影，不扫描遥测堆栈。 */
    Progress progress(UUID appId, String buildId);

    /** 当前轮次的累计处理数量；idle 不代表不会再出现迟到事件。 */
    record Progress(
            /** queued/running/waiting_mapping/retry_wait/idle。 */ String state,
            /** 本轮首次成功发布确认的事件数量。 */ long succeededCount,
            /** 本轮已登记的处理失败尝试数，包含之后写回未确认或再次重试。 */ long failedCount,
            /** 管理记录更新时间。 */ Instant updatedAt,
            /** 安全错误类别。 */ String errorCategory) {}
}
