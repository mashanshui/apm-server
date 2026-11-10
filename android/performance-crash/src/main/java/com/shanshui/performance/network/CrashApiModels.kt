package com.shanshui.performance.network

data class CrashBatchRequest(
    val requestId: String,
    val events: List<CrashEvent>,
)

data class CrashEvent(
    val schemaVersion: Int,
    val eventId: String,
    val eventType: String,
    val occurredAt: Long,
    val sessionId: String,
    /** 当前 Android 进程的稳定身份，服务端要求为 UUID v4。 */
    val processId: String,
    val anonymousDeviceId: String,
    val packageName: String,
    val appVersion: String,
    val versionCode: Int,
    val buildId: String,
    val environment: String,
    val channel: String,
    val osVersion: String,
    val deviceModel: String,
    val networkType: String? = null,
    val measurements: Map<String, Double>? = null,
    val attributes: Map<String, String>? = null,
    val crash: CrashPayload? = null,
)

data class CrashPayload(
    val kind: String = "jvm",
    val fatal: Boolean = true,
    /** JVM 异常链，与 Native 原始材料互斥。 */
    val throwableChain: List<ThrowableNode>? = null,
    /** 可选的崩溃现场；缺失时仍可上报核心异常。 */
    val diagnostics: CrashDiagnostics? = null,
    /** Native 原始信号、寄存器与回溯。 */
    val nativeCrash: NativeCrash? = null,
)

data class ThrowableNode(
    val type: String,
    val message: String? = null,
    val frames: List<StackFrame>,
)

data class StackFrame(
    val className: String,
    val methodName: String,
    val fileName: String? = null,
    val lineNumber: Int? = null,
    val applicationFrame: Boolean? = null,
)

data class CrashBatchResponse(
    val requestId: String? = null,
    val accepted: Int = 0,
    val rejected: Int = 0,
    val duplicate: Int = 0,
    val retryable: Boolean = false,
    val retryAfterSeconds: Long? = null,
    val errors: List<BatchError> = emptyList(),
)

data class BatchError(
    val index: Int? = null,
    val eventId: String? = null,
    val code: String? = null,
    val message: String? = null,
    val retryable: Boolean = false,
)
