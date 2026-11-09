package com.shanshui.performance.crash

import com.shanshui.performance.network.CrashEvent
import com.shanshui.performance.network.CrashPayload
import com.shanshui.performance.network.ThrowableNode
import com.shanshui.performance.network.StackFrame
import com.shanshui.performance.network.CrashDiagnostics
import com.shanshui.performance.network.CrashedThread
import com.shanshui.performance.network.CrashProcess
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class FileCrashQueueTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun queueWritesRecordsAtomicallyAndTracksRetryState() {
        val queue = FileCrashQueue(temporaryFolder.newFolder("queue"))
        val event = sampleEvent("event-1")

        assertTrue(queue.enqueue(event, createdAtMillis = 100L))
        assertFalse(queue.enqueue(event, createdAtMillis = 200L))
        assertEquals(1, queue.count())

        val item = queue.peekBatch(limit = 1, nowMillis = 100L).single()
        assertEquals(0, item.record.attempts)
        queue.markRetry(listOf(item), nextAttemptAtMillis = 10_000L)

        assertTrue(queue.peekBatch(limit = 1, nowMillis = 1_000L).isEmpty())
        val retried = queue.peekBatch(limit = 1, nowMillis = 10_000L).single()
        assertEquals(1, retried.record.attempts)

        queue.acknowledge(listOf(event.eventId))
        assertEquals(0, queue.count())
    }

    @Test
    fun deadLetterRemovesEventsWithoutLoggingPayload() {
        val root = temporaryFolder.newFolder("queue")
        val queue = FileCrashQueue(root)
        val event = sampleEvent("event-2")
        queue.enqueue(event)

        val item = queue.peekBatch(1).single()
        queue.moveToDeadLetter(listOf(item), "http_401")

        assertEquals(0, queue.count())
        val deadLetters = File(root, "dead-letter").listFiles().orEmpty()
        assertEquals(1, deadLetters.size)
        val deadLetterText = deadLetters.single().readText()
        assertTrue(deadLetterText.contains("http_401"))
        assertTrue(deadLetterText.contains("event-2"))
    }

    /** 未冻结的核心异常不可上传，中断恢复保留原身份、时间和过期窗口。 */
    @Test
    fun interruptedCaptureRecoversOriginalCoreBeforeUpload() {
        // 用较早创建时间模拟旧进程捕获，重启只恢复不采集新现场。
        val root = temporaryFolder.newFolder("capture-recovery")
        val queue = FileCrashQueue(root)
        val base = sampleCrash("event-recovery")
        val event = base.copy(crash = base.crash!!.copy(diagnostics = CrashDiagnostics(
            crashedThread = CrashedThread(status = "ok", javaId = "12", name = "worker", state = "RUNNABLE", systemTid = 42),
            process = CrashProcess(status = "ok", pid = 123, name = "com.example"),
        )))
        assertTrue(queue.beginCapture(event, createdAtMillis = 100L))
        assertEquals(0, queue.count())
        assertTrue(queue.peekBatch(1).isEmpty())
        assertEquals(1, FileCrashQueue(root).recoverCaptures())
        val restored = queue.peekBatch(1).single().record
        assertEquals(100L, restored.createdAtMillis)
        assertEquals(event.eventId, restored.event.eventId)
        assertEquals(event.processId, restored.event.processId)
        assertEquals(event.occurredAt, restored.event.occurredAt)
        assertEquals("12", restored.event.crash?.diagnostics?.crashedThread?.javaId)
        assertEquals(42, restored.event.crash?.diagnostics?.crashedThread?.systemTid)
        assertEquals(123, restored.event.crash?.diagnostics?.process?.pid)
        assertEquals("interrupted", restored.event.crash?.diagnostics?.otherThreads?.reason)
        queue.expireOlderThan(101L)
        assertEquals(0, queue.count())
    }

    /** 已冻结记录优先，重复采集中记录与写入未完成的临时文件不能覆盖它。 */
    @Test
    fun recoveryKeepsFrozenEventAndDiscardsInterruptedTemporaryFiles() {
        val root = temporaryFolder.newFolder("capture-duplicate")
        val queue = FileCrashQueue(root)
        val event = sampleCrash("event-duplicate")
        assertTrue(queue.beginCapture(event, createdAtMillis = 100L))
        val capturing = File(root, "capturing/event-duplicate.json")
        val original = capturing.readText()
        assertTrue(queue.freezeCapture(event))
        capturing.writeText(original)
        File(root, "capturing/event-late.json.tmp-incomplete").writeText("{partial")
        File(root, "events/event-late.json.tmp-incomplete").writeText("{partial")
        assertEquals(0, FileCrashQueue(root).recoverCaptures())
        assertFalse(capturing.exists())
        assertTrue(File(root, "capturing").listFiles().orEmpty().isEmpty())
        assertFalse(File(root, "events/event-late.json.tmp-incomplete").exists())
        assertEquals(event, queue.peekBatch(1).single().record.event)
        assertFalse(queue.beginCapture(event))
    }

    /** 冻结不得更改已经可靠保存的核心异常，失败后仍可恢复原始记录。 */
    @Test
    fun freezeCannotReplaceCapturedCore() {
        val root = temporaryFolder.newFolder("capture-core")
        val queue = FileCrashQueue(root)
        val event = sampleCrash("event-core")
        assertTrue(queue.beginCapture(event, 100L))
        assertThrows(IllegalArgumentException::class.java) {
            queue.freezeCapture(event.copy(sessionId = "different-session"))
        }
        assertEquals(0, queue.count())
        assertEquals(1, queue.recoverCaptures())
        assertEquals(event.sessionId, queue.peekBatch(1).single().record.event.sessionId)
    }

    /** 损坏的采集中记录安全隔离，不能阻止其他核心异常恢复。 */
    @Test
    fun recoveryIsolatesMalformedCapture() {
        val root = temporaryFolder.newFolder("capture-malformed")
        val queue = FileCrashQueue(root)
        File(root, "capturing/broken.json").writeText("{bad")
        assertTrue(queue.beginCapture(sampleCrash("event-good"), 100L))
        assertEquals(1, queue.recoverCaptures())
        assertEquals(1, queue.count())
        assertTrue(File(root, "dead-letter").listFiles().orEmpty().single().readText().contains("malformed_queue_record"))
    }

    /** 构造不含真实设备信息的核心 Crash。 */
    private fun sampleCrash(eventId: String): CrashEvent = sampleEvent(eventId).copy(
        eventType = "crash",
        crash = CrashPayload(throwableChain = listOf(
            ThrowableNode("java.lang.IllegalStateException", frames = listOf(StackFrame("A", "run"))),
        )),
    )

    private fun sampleEvent(eventId: String): CrashEvent {
        return CrashEvent(
            schemaVersion = 2,
            eventId = eventId,
            eventType = "app_start",
            occurredAt = 1_726_000_000_000,
            sessionId = "session-1",
            processId = "11111111-1111-4111-8111-111111111111",
            anonymousDeviceId = "install-1",
            packageName = "com.shanshui.performance",
            appVersion = "1.0",
            versionCode = 1,
            buildId = "build-1",
            environment = "test",
            channel = "unit-test",
            osVersion = "35",
            deviceModel = "Pixel Test",
        )
    }
}
