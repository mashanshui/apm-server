package com.shanshui.apmserver.crash.api;

import java.time.Instant;
import java.util.UUID;

/** 符号表管理与接收方共用的持久化版本唤醒边界。 */
public interface CrashSymbolicationWork {
    /** 必须与符号表元数据事务共同提交，不执行历史查询或 Retrace。 */
    void wake(UUID appId, String appVersion);

    /** 在调用者事务内合并同版本 Native 工作通知。 */
    void wakeNative(UUID appId, String appVersion);

    /** 只读一个版本的任务投影，不扫描遥测堆栈。 */
    Progress progress(UUID appId, String appVersion);

    /** 按同一应用版本读取 Native 发布和失败单元进度。 */
    NativeProgress nativeProgress(UUID appId, String appVersion);

    /** 当前轮次的累计处理数量；idle 不代表不会再出现迟到事件。 */
    record Progress(
            /** queued/running/waiting_mapping/retry_wait/idle。 */ String state,
            /** 本轮首次成功发布确认的事件数量。 */ long succeededCount,
            /** 本轮已登记的处理失败尝试数，包含之后写回未确认或再次重试。 */ long failedCount,
            /** 管理记录更新时间。 */ Instant updatedAt,
            /** 安全错误类别。 */ String errorCategory) {}

    /** 同版本 SO 共用的 Native 当前轮次进度。 */
    record NativeProgress(
            /** 固定为 native。 */ String crashKind,
            /** queued/running/waiting_symbols/retry_wait/idle。 */ String state,
            /** 已确认完整状态更新次数。 */ long publishedCount,
            /** 已确认的新增失败单元次数。 */ long failedUnitCount,
            /** 管理记录更新时间。 */ Instant updatedAt,
            /** 安全错误类别。 */ String errorCategory) {}
}
