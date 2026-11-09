package com.shanshui.apmserver.crash.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.shanshui.apmserver.telemetry.api.StackFrame;

import java.util.List;

/** 入站 JVM 崩溃现场；未采集的分区为 null，不合成成功的空快照。 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CrashDiagnostics(
        /** 主异常因本地预算被裁剪时的说明。 */ CoreTruncation coreTruncation,
        /** 崩溃线程身份。 */ CrashedThread crashedThread,
        /** 其他 JVM 线程及原始帧。 */ OtherThreads otherThreads,
        /** 当前进程可读取的日志。 */ Logcat logcat,
        /** 内存快照。 */ Memory memory,
        /** 当前进程文件描述符。 */ OpenFiles openFiles,
        /** 数值进程与生命周期信息。 */ Process process,
        /** 设备和系统构建信息。 */ Device device) {

    /** 核心异常裁剪信息。 */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record CoreTruncation(
            /** 是否发生裁剪。 */ Boolean truncated,
            /** 安全原因类别。 */ String reason) { }

    /** 未捕获异常处理器收到的线程。 */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record CrashedThread(
            /** 采集状态。 */ String status,
            /** 安全原因类别。 */ String reason,
            /** 是否裁剪。 */ Boolean truncated,
            /** 原采集时间，Unix 毫秒。 */ Long capturedAt,
            /** 十进制字符串形式的 Java 线程标识。 */ String javaId,
            /** 已脱敏线程名。 */ String name,
            /** Java 线程状态。 */ String state,
            /** Java 线程优先级。 */ Integer priority,
            /** 是否为守护线程。 */ Boolean daemon,
            /** 是否为 Android 主线程。 */ Boolean mainThread,
            /** 处理器入口取得的系统 TID。 */ Integer systemTid) { }

    /** 其他线程集合和可知的观测计数。 */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record OtherThreads(
            /** 采集状态。 */ String status,
            /** 安全原因类别。 */ String reason,
            /** 是否裁剪。 */ Boolean truncated,
            /** 原采集时间，Unix 毫秒。 */ Long capturedAt,
            /** 可知的线程总数。 */ Integer observedCount,
            /** 可知的遗漏数。 */ Integer omittedCount,
            /** 保留采集顺序的线程快照。 */ List<OtherThread> threads) { }

    /** 单个其他线程的 Java 身份及可能为空的原始帧。 */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record OtherThread(
            /** 十进制字符串形式的 Java 线程标识。 */ String javaId,
            /** 已脱敏线程名。 */ String name,
            /** Java 线程状态。 */ String state,
            /** Java 线程优先级。 */ Integer priority,
            /** 是否为守护线程。 */ Boolean daemon,
            /** 是否为 Android 主线程。 */ Boolean mainThread,
            /** 此线程的帧是否裁剪。 */ Boolean truncated,
            /** 原始 Java 帧。 */ List<StackFrame> frames) { }

    /** 三个日志缓冲区各自保留状态。 */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Logcat(
            /** 采集状态。 */ String status,
            /** 安全原因类别。 */ String reason,
            /** 是否裁剪。 */ Boolean truncated,
            /** 原采集时间，Unix 毫秒。 */ Long capturedAt,
            /** main 缓冲区。 */ LogcatBuffer main,
            /** system 缓冲区。 */ LogcatBuffer system,
            /** events 缓冲区。 */ LogcatBuffer events) { }

    /** 单个已脱敏日志缓冲区。 */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record LogcatBuffer(
            /** 采集状态。 */ String status,
            /** 安全原因类别。 */ String reason,
            /** 是否裁剪。 */ Boolean truncated,
            /** 原采集时间，Unix 毫秒。 */ Long capturedAt,
            /** 有界日志行。 */ List<String> lines) { }

    /** 统一以字节表达的内存摘要和受限 proc 原文。 */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Memory(
            /** 采集状态。 */ String status,
            /** 安全原因类别。 */ String reason,
            /** 是否裁剪。 */ Boolean truncated,
            /** 原采集时间，Unix 毫秒。 */ Long capturedAt,
            /** Runtime Java 堆已使用字节数。 */ Long javaHeapUsedBytes,
            /** Runtime Java 堆最大字节数。 */ Long javaHeapMaxBytes,
            /** Debug Java 堆 PSS 字节数。 */ Long javaHeapPssBytes,
            /** Debug Native 堆 PSS 字节数。 */ Long nativeHeapPssBytes,
            /** Debug Native 堆已分配字节数。 */ Long nativeHeapAllocatedBytes,
            /** 进程总 PSS 字节数。 */ Long totalPssBytes,
            /** 进程 RSS 字节数。 */ Long rssBytes,
            /** 进程 VSS 字节数。 */ Long vssBytes,
            /** 进程 Swap 字节数。 */ Long swapBytes,
            /** 已脱敏的 meminfo 原文。 */ String meminfo,
            /** 已脱敏的 proc status 原文。 */ String statusText,
            /** 已脱敏的 proc limits 原文。 */ String limits) { }

    /** 文件描述符计数和有限明细。 */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record OpenFiles(
            /** 采集状态。 */ String status,
            /** 安全原因类别。 */ String reason,
            /** 是否裁剪。 */ Boolean truncated,
            /** 原采集时间，Unix 毫秒。 */ Long capturedAt,
            /** 可知的 FD 总数。 */ Integer count,
            /** 有界明细。 */ List<OpenFile> entries) { }

    /** 单个文件描述符及安全目标描述。 */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record OpenFile(
            /** 文件描述符编号。 */ Integer fd,
            /** 已脱敏的目标描述。 */ String target) { }

    /** 数值进程、可知前后台状态及两种时间来源。 */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Process(
            /** 采集状态。 */ String status,
            /** 安全原因类别。 */ String reason,
            /** 是否裁剪。 */ Boolean truncated,
            /** 原采集时间，Unix 毫秒。 */ Long capturedAt,
            /** Android 数值进程号。 */ Integer pid,
            /** 已脱敏进程名。 */ String name,
            /** 可知时的前后台状态。 */ Boolean foreground,
            /** 可靠取得的系统进程启动时间，Unix 毫秒。 */ Long startedAt,
            /** SDK 初始化时间，Unix 毫秒。 */ Long sdkInitializedAt) { }

    /** 公开设备环境。 */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Device(
            /** 采集状态。 */ String status,
            /** 安全原因类别。 */ String reason,
            /** 是否裁剪。 */ Boolean truncated,
            /** 原采集时间，Unix 毫秒。 */ Long capturedAt,
            /** Android API level。 */ Integer apiLevel,
            /** 设备支持的 ABI。 */ List<String> abis,
            /** 已脱敏厂商。 */ String manufacturer,
            /** 已脱敏品牌。 */ String brand,
            /** 已脱敏系统构建标识。 */ String buildFingerprint) { }
}
