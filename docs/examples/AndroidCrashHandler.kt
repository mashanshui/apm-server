package com.example.apm

import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 首期 Java/Kotlin Crash Handler 联调样例。
 * 真实 SDK 需要把 PendingEventQueue 替换为文件原子写入实现，并在进程退出前完成同步落盘。
 */
class AndroidCrashHandler(
    private val queue: PendingEventQueue,
    private val sender: BatchSender
) : Thread.UncaughtExceptionHandler {

    private val handling = AtomicBoolean(false)
    private val previousHandler = Thread.getDefaultUncaughtExceptionHandler()

    override fun uncaughtException(thread: Thread, throwable: Throwable) {
        if (!handling.compareAndSet(false, true)) return

        val eventId = UUID.randomUUID().toString()
        val event = CrashEventFactory.fromThrowable(eventId, throwable)
        // 先持久化，再尝试发送；发送失败时不删除事件。
        queue.append(event)
        runCatching { flush() }
        // 这里交回系统默认 Handler，避免改变 Android 对进程终止的语义。
        previousHandler?.uncaughtException(thread, throwable)
    }

    fun flush() {
        val batch = queue.peekBatch(50)
        if (batch.isEmpty()) return
        val result = sender.send(batch)
        // 永久错误可以确认删除；临时错误保留原 eventId，等待下次启动重试。
        queue.acknowledge(result.acceptedEventIds + result.permanentErrorEventIds)
    }
}

interface PendingEventQueue {
    fun append(event: CrashEvent)
    fun peekBatch(limit: Int): List<CrashEvent>
    fun acknowledge(eventIds: List<String>)
}

interface BatchSender {
    fun send(events: List<CrashEvent>): SendResult
}

data class SendResult(
    val acceptedEventIds: List<String>,
    val permanentErrorEventIds: List<String>
)

data class CrashEvent(
    val eventId: String,
    val sessionId: String,
    val anonymousDeviceId: String,
    val occurredAt: Long,
    val appVersion: String,
    val versionCode: Int,
    val buildId: String,
    val throwableChain: List<ThrowableNode>
)

data class ThrowableNode(
    val type: String,
    val message: String?,
    val frames: List<StackFrame>
)

data class StackFrame(
    val className: String,
    val methodName: String,
    val fileName: String?,
    val lineNumber: Int?,
    val applicationFrame: Boolean
)

object CrashEventFactory {
    fun fromThrowable(eventId: String, throwable: Throwable): CrashEvent {
        // 示例省略 Android Build/Session 实际取值；消息和堆栈仍应在客户端先做长度限制。
        return CrashEvent(
            eventId = eventId,
            sessionId = "session-from-sdk",
            anonymousDeviceId = "device-from-sdk",
            occurredAt = System.currentTimeMillis(),
            appVersion = "3.2.0",
            versionCode = 320,
            buildId = "build-320",
            throwableChain = listOf(ThrowableNode(
                type = throwable.javaClass.name,
                message = throwable.message,
                frames = throwable.stackTrace.map { frame ->
                    StackFrame(frame.className, frame.methodName, frame.fileName, frame.lineNumber, true)
                }
            ))
        )
    }
}
