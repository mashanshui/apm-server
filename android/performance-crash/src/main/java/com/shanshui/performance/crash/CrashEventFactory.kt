package com.shanshui.performance.crash

import com.shanshui.performance.diagnostics.DiagnosticSanitizer

import com.shanshui.performance.network.CrashEvent
import com.shanshui.performance.network.CrashPayload
import com.shanshui.performance.network.StackFrame
import com.shanshui.performance.network.ThrowableNode
import java.util.IdentityHashMap
import java.util.UUID

internal data class CrashDeviceInfo(
    val osVersion: String,
    val deviceModel: String,
)

internal class CrashEventFactory(
    private val config: CrashReporterConfig,
    private val serviceConfig: CrashServiceConfig,
    private val sessionId: String,
    /** 当前 Android 进程的稳定身份。 */
    private val processId: String,
    private val anonymousDeviceId: String,
    private val deviceInfo: CrashDeviceInfo,
    private val networkTypeProvider: () -> String?,
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val idGenerator: () -> String = { UUID.randomUUID().toString() },
) {
    fun appStart(): CrashEvent {
        return baseEvent(
            eventId = idGenerator(),
            eventType = EVENT_TYPE_APP_START,
            occurredAt = clock(),
        )
    }

    /** 为下一次 Native 崩溃预留固定事件身份；发生时间由信号现场覆盖。 */
    fun nativeIdentity(): CrashEvent = baseEvent(
        eventId = idGenerator(),
        eventType = EVENT_TYPE_CRASH,
        occurredAt = clock(),
    ).let { event ->
        // 标识字段保持精确；其余可由设备或应用提供的文本在首次暂存前脱敏。
        event.copy(appVersion = DiagnosticSanitizer.sanitize(event.appVersion, MAX_TEXT_LENGTH),
            environment = DiagnosticSanitizer.sanitize(event.environment, MAX_TEXT_LENGTH),
            channel = DiagnosticSanitizer.sanitize(event.channel, MAX_CHANNEL_LENGTH),
            osVersion = DiagnosticSanitizer.sanitize(event.osVersion, MAX_TEXT_LENGTH),
            deviceModel = DiagnosticSanitizer.sanitize(event.deviceModel, MAX_DEVICE_MODEL_LENGTH),
            networkType = event.networkType?.let { DiagnosticSanitizer.sanitize(it, MAX_NETWORK_TYPE_LENGTH) })
    }

    fun crash(throwable: Throwable): CrashEvent {
        return baseEvent(
            eventId = idGenerator(),
            eventType = EVENT_TYPE_CRASH,
            occurredAt = clock(),
        ).copy(
            crash = CrashPayload(
                kind = "jvm",
                fatal = true,
                throwableChain = ThrowableMapper(config.applicationPackage).map(throwable),
            ),
        )
    }

    private fun baseEvent(
        eventId: String,
        eventType: String,
        occurredAt: Long,
    ): CrashEvent {
        return CrashEvent(
            schemaVersion = serviceConfig.schemaVersion,
            eventId = eventId.take(MAX_EVENT_ID_LENGTH),
            eventType = eventType,
            occurredAt = occurredAt,
            sessionId = sessionId.take(MAX_SESSION_ID_LENGTH),
            processId = processId.take(MAX_PROCESS_ID_LENGTH),
            anonymousDeviceId = anonymousDeviceId.take(MAX_DEVICE_ID_LENGTH),
            packageName = config.packageName,
            appVersion = config.appVersion.take(MAX_TEXT_LENGTH),
            versionCode = config.versionCode,
            buildId = config.buildId.take(MAX_BUILD_ID_LENGTH),
            environment = serviceConfig.environment.take(MAX_TEXT_LENGTH),
            channel = serviceConfig.channel.take(MAX_CHANNEL_LENGTH),
            osVersion = deviceInfo.osVersion.take(MAX_TEXT_LENGTH),
            deviceModel = deviceInfo.deviceModel.take(MAX_DEVICE_MODEL_LENGTH),
            networkType = networkTypeProvider()?.take(MAX_NETWORK_TYPE_LENGTH),
        )
    }

    private companion object {
        const val EVENT_TYPE_CRASH = "crash"
        const val EVENT_TYPE_APP_START = "app_start"
        const val MAX_EVENT_ID_LENGTH = 128
        const val MAX_SESSION_ID_LENGTH = 128
        const val MAX_PROCESS_ID_LENGTH = 128
        const val MAX_DEVICE_ID_LENGTH = 256
        const val MAX_BUILD_ID_LENGTH = 256
        const val MAX_CHANNEL_LENGTH = 128
        const val MAX_DEVICE_MODEL_LENGTH = 256
        const val MAX_NETWORK_TYPE_LENGTH = 32
        const val MAX_TEXT_LENGTH = 128
    }
}

internal class ThrowableMapper(
    private val applicationPackage: String,
) {
    fun map(root: Throwable): List<ThrowableNode> {
        val nodes = mutableListOf<ThrowableNode>()
        val visited = IdentityHashMap<Throwable, Boolean>()
        var current: Throwable? = root
        var totalFrames = 0

        while (current != null && nodes.size < MAX_THROWABLE_CHAIN && !visited.containsKey(current)) {
            visited[current] = true
            val throwable = current
            val rawFrames = throwable.stackTrace.orEmpty()
            val frames = if (rawFrames.isEmpty()) {
                listOf(syntheticFrame(throwable))
            } else {
                rawFrames
                    .take((MAX_TOTAL_FRAMES - totalFrames).coerceAtLeast(0))
                    .map(::mapFrame)
                    .ifEmpty { listOf(syntheticFrame(throwable)) }
            }
            if (totalFrames + frames.size > MAX_TOTAL_FRAMES) {
                break
            }
            totalFrames += frames.size
            nodes += ThrowableNode(
                type = throwable.javaClass.name.take(MAX_TYPE_LENGTH),
                message = throwable.message
                    ?.let { DiagnosticSanitizer.sanitize(it, MAX_MESSAGE_LENGTH) }
                    ?.takeIf { it.isNotBlank() },
                frames = frames,
            )
            current = throwable.cause
        }

        return nodes.ifEmpty {
            listOf(
                ThrowableNode(
                    type = root.javaClass.name.take(MAX_TYPE_LENGTH),
                    message = root.message?.let { DiagnosticSanitizer.sanitize(it, MAX_MESSAGE_LENGTH) },
                    frames = listOf(syntheticFrame(root)),
                ),
            )
        }
    }

    private fun mapFrame(frame: StackTraceElement): StackFrame {
        val className = frame.className.ifBlank { "<unknown>" }.take(MAX_FRAME_TEXT_LENGTH)
        return StackFrame(
            className = className,
            methodName = frame.methodName.ifBlank { "<unknown>" }.take(MAX_FRAME_TEXT_LENGTH),
            fileName = frame.fileName?.let {
                DiagnosticSanitizer.sanitize(it, MAX_FRAME_TEXT_LENGTH)
            },
            lineNumber = frame.lineNumber.takeIf { it in 0..MAX_LINE_NUMBER } ?: -1,
            applicationFrame = isApplicationFrame(className),
        )
    }

    private fun syntheticFrame(throwable: Throwable): StackFrame {
        return StackFrame(
            className = throwable.javaClass.name.take(MAX_FRAME_TEXT_LENGTH),
            methodName = "<unknown>",
            lineNumber = -1,
            applicationFrame = false,
        )
    }

    private fun isApplicationFrame(className: String): Boolean {
        return className == applicationPackage || className.startsWith("$applicationPackage.")
    }

    private companion object {
        const val MAX_THROWABLE_CHAIN = 16
        const val MAX_TOTAL_FRAMES = 200
        const val MAX_TYPE_LENGTH = 512
        const val MAX_MESSAGE_LENGTH = 4096
        const val MAX_FRAME_TEXT_LENGTH = 512
        const val MAX_LINE_NUMBER = 1_000_000_000
    }
}
