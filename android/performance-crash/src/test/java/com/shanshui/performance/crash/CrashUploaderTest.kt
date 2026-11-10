package com.shanshui.performance.crash

import com.shanshui.performance.network.BatchError
import com.shanshui.performance.network.CrashBatchResponse
import com.shanshui.performance.network.CrashEvent
import com.shanshui.performance.network.CrashDiagnostics
import com.shanshui.performance.network.CrashLogcat
import com.shanshui.performance.network.CrashPayload
import com.shanshui.performance.network.CrashProcess
import com.shanshui.performance.network.CrashedThread
import com.shanshui.performance.network.LogcatBuffer
import com.shanshui.performance.network.NetworkResult
import com.shanshui.performance.network.NativeBacktrace
import com.shanshui.performance.network.NativeCrash
import com.shanshui.performance.network.NativeRegisters
import com.shanshui.performance.network.NativeSignal
import com.shanshui.performance.network.StackFrame
import com.shanshui.performance.network.ThrowableNode
import java.util.concurrent.atomic.AtomicReference
import java.io.IOException
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class CrashUploaderTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun partialSuccessAcknowledgesAcceptedAndDeadLettersPermanentEvent() = runBlocking {
        val queue = FileCrashQueue(temporaryFolder.newFolder("queue"))
        queue.enqueue(sampleEvent("event-1"))
        queue.enqueue(sampleEvent("event-2"))
        val sent = AtomicReference<com.shanshui.performance.network.CrashBatchRequest>()
        val uploader = uploader(queue) { request ->
            sent.set(request)
            NetworkResult.Success(
                CrashBatchResponse(
                    requestId = "request-1",
                    accepted = 1,
                    rejected = 1,
                    errors = listOf(
                        BatchError(index = 1, eventId = "event-2", code = "INVALID_BATCH"),
                    ),
                ),
                statusCode = 200,
            )
        }

        val report = uploader.flush(maxBatches = 1)

        assertEquals(2, sent.get().events.size)
        assertEquals(1, report.eventsAcknowledged)
        assertEquals(1, report.eventsDeadLettered)
        assertEquals(0, queue.count())
    }

    @Test
    fun retryableHttpErrorKeepsOriginalEventIdAndSchedulesRetry() = runBlocking {
        val queue = FileCrashQueue(temporaryFolder.newFolder("queue"))
        queue.enqueue(sampleEvent("event-3"))
        val uploader = uploader(queue) {
            NetworkResult.HttpError(
                statusCode = 503,
                responseBody = null,
                retryable = true,
                retryAfterSeconds = 60,
            )
        }

        val report = uploader.flush(maxBatches = 1)
        val item = queue.peekBatch(1, nowMillis = 1_726_000_000_000).singleOrNull()

        assertEquals(1, report.eventsRetried)
        assertEquals(1, queue.count())
        assertTrue(item == null)
        assertEquals("event-3", queue.peekBatch(1, Long.MAX_VALUE).single().record.event.eventId)
        assertEquals(1, queue.peekBatch(1, Long.MAX_VALUE).single().record.attempts)
    }

    @Test
    fun invalidSuccessResponseIsRetriedInsteadOfAcknowledged() = runBlocking {
        val queue = FileCrashQueue(temporaryFolder.newFolder("queue"))
        queue.enqueue(sampleEvent("event-4"))
        val uploader = uploader(queue) {
            NetworkResult.Success(
                CrashBatchResponse(
                    accepted = 1,
                    errors = listOf(BatchError(index = 9, retryable = false)),
                ),
                statusCode = 200,
            )
        }

        val report = uploader.flush(maxBatches = 1)

        assertEquals(1, report.eventsRetried)
        assertEquals(1, queue.count())
        assertEquals(1, queue.peekBatch(1, Long.MAX_VALUE).single().record.attempts)
    }

    @Test
    fun incompleteSuccessCountsAreRetriedInsteadOfAcknowledged() = runBlocking {
        val queue = FileCrashQueue(temporaryFolder.newFolder("queue"))
        queue.enqueue(sampleEvent("event-5"))
        val uploader = uploader(queue) {
            NetworkResult.Success(
                CrashBatchResponse(requestId = "request-1"),
                statusCode = 200,
            )
        }

        val report = uploader.flush(maxBatches = 1)

        assertEquals(1, report.eventsRetried)
        assertEquals(1, queue.count())
        assertEquals(1, queue.peekBatch(1, Long.MAX_VALUE).single().record.attempts)
    }

    /** 未冻结不可见；离线重启后原身份和现场随 accepted/duplicate 确认删除。 */
    @Test
    fun retriesOriginalFrozenOrInterruptedCaptureAfterRestart() = runBlocking {
        for (interrupted in listOf(true, false)) {
            val root = temporaryFolder.newFolder(if (interrupted) "interrupted" else "frozen")
            val queue = FileCrashQueue(root)
            val identity = CrashDiagnostics(
                crashedThread = CrashedThread(status = "ok", javaId = "12", name = "worker", state = "RUNNABLE", systemTid = 42),
                process = CrashProcess(status = "ok", pid = 123, name = "com.example"),
            )
            val core = sampleEvent("event-retry").copy(crash = CrashPayload(
                throwableChain = listOf(ThrowableNode("E", frames = listOf(StackFrame("A", "run")))), diagnostics = identity,
            ))
            assertTrue(queue.beginCapture(core, createdAtMillis = 1_726_000_000_000))
            var sends = 0
            val hiddenUploader = uploader(queue) { sends++; error("capturing must be invisible") }
            hiddenUploader.flush(maxBatches = 1)
            assertEquals(0, sends)
            if (interrupted) {
                assertEquals(1, FileCrashQueue(root).recoverCaptures())
            } else {
                val snapshot = identity.copy(logcat = CrashLogcat(status = "ok",
                    main = LogcatBuffer(status = "ok", lines = listOf("synthetic"))))
                assertTrue(queue.freezeCapture(core.copy(crash = core.crash!!.copy(diagnostics = snapshot))))
            }
            val frozen = queue.peekBatch(1, Long.MAX_VALUE).single().record.event
            val offline = uploader(queue) {
                NetworkResult.HttpError(503, null, retryable = true, retryAfterSeconds = 0)
            }
            assertEquals(1, offline.flush(maxBatches = 1).eventsRetried)
            val restarted = FileCrashQueue(root)
            val sent = AtomicReference<CrashEvent>()
            val retry = uploader(restarted) { request ->
                sent.set(request.events.single())
                NetworkResult.Success(CrashBatchResponse(requestId = request.requestId,
                    accepted = if (interrupted) 1 else 0, duplicate = if (interrupted) 0 else 1), 200)
            }
            assertEquals(1, retry.flush(maxBatches = 1).eventsAcknowledged)
            assertEquals(frozen, sent.get())
            assertEquals(core.sessionId, sent.get().sessionId)
            assertEquals(core.processId, sent.get().processId)
            assertEquals(42, sent.get().crash!!.diagnostics!!.crashedThread!!.systemTid)
            assertEquals(123, sent.get().crash!!.diagnostics!!.process!!.pid)
            assertEquals(if (interrupted) "interrupted" else "ok", if (interrupted)
                sent.get().crash!!.diagnostics!!.otherThreads!!.reason else sent.get().crash!!.diagnostics!!.logcat!!.status)
            assertEquals(0, restarted.count())
        }
    }

    /** Native 冻结内容在断网、限流、服务端错误和伪造计数后原样重试，确认丢失按 duplicate 删除。 */
    @Test
    fun retriesFrozenNativeEventsAcrossNetworkAndInvalidAcknowledgements() = runBlocking {
        val failures: List<Pair<String, NetworkResult<CrashBatchResponse>>> = listOf(
            "offline" to NetworkResult.NetworkError(IOException("synthetic offline")),
            "408" to NetworkResult.HttpError(408, null, true, 0),
            "429" to NetworkResult.HttpError(429, null, true, 0),
            "503" to NetworkResult.HttpError(503, null, true, 0),
            "forged-count" to NetworkResult.Success(CrashBatchResponse(requestId = "request-1", accepted = 2), 200),
        )
        for ((label, failure) in failures) {
            val root = temporaryFolder.newFolder("native-$label")
            val queue = FileCrashQueue(root)
            val original = nativeEvent("native-$label")
            queue.enqueue(original, createdAtMillis = 1_726_000_000_000)
            val first = AtomicReference<CrashEvent>()
            val failing = uploader(queue) { request -> first.set(request.events.single()); failure }
            assertEquals(1, failing.flush(maxBatches = 1).eventsRetried)
            assertEquals(original, first.get())
            assertEquals(original, queue.peekBatch(1, Long.MAX_VALUE).single().record.event)
            val second = AtomicReference<CrashEvent>()
            val retried = uploader(FileCrashQueue(root), clock = { 1_726_000_100_000 }) { request ->
                second.set(request.events.single())
                NetworkResult.Success(CrashBatchResponse(requestId = request.requestId, duplicate = 1), 200)
            }
            assertEquals(1, retried.flush(maxBatches = 1).eventsAcknowledged)
            assertEquals(original, second.get())
            assertEquals(0, queue.count())
        }
    }

    /** 永久 HTTP 错误将 Native 事件转入 dead-letter，不误当作确认。 */
    @Test
    fun deadLettersNativeEventOnPermanentHttpError() = runBlocking {
        val root = temporaryFolder.newFolder("native-permanent")
        val queue = FileCrashQueue(root)
        queue.enqueue(nativeEvent("native-permanent"), createdAtMillis = 1_726_000_000_000)
        val uploader = uploader(queue) { NetworkResult.HttpError(400, null, false) }
        assertEquals(1, uploader.flush(maxBatches = 1).eventsDeadLettered)
        assertEquals(0, queue.count())
        assertTrue(File(root, "dead-letter").listFiles().orEmpty().isNotEmpty())
    }

    /** Native 采集中不发起新请求，采集结束后原冻结事件仍可上传。 */
    @Test
    fun doesNotStartUploadWhileNativeCaptureIsInProgress() = runBlocking {
        val queue = FileCrashQueue(temporaryFolder.newFolder("native-capture-gate"))
        val event = nativeEvent("native-capture-gate")
        queue.enqueue(event, createdAtMillis = 1_726_000_000_000)
        var capturing = true
        var sends = 0
        val uploader = uploader(queue, canSend = { !capturing }) { request ->
            sends++
            assertEquals(event, request.events.single())
            NetworkResult.Success(CrashBatchResponse(requestId = request.requestId, accepted = 1), 200)
        }
        assertEquals(0, uploader.flush(maxBatches = 1).batchesSent)
        assertEquals(0, sends)
        assertEquals(1, queue.count())
        capturing = false
        assertEquals(1, uploader.flush(maxBatches = 1).eventsAcknowledged)
        assertEquals(1, sends)
    }

    private fun uploader(
        queue: FileCrashQueue,
        clock: () -> Long = { 1_726_000_000_000 },
        canSend: () -> Boolean = { true },
        sender: CrashBatchSender,
    ): CrashUploader {
        return CrashUploader(
            queue = queue,
            sender = sender,
            config = CrashServiceConfig(
                baseUrl = "https://example.test/",
                appKey = "test-app-key",
                environment = "test",
                channel = "unit-test",
                schemaVersion = 2,
                enableNetworkLogging = false,
                connectTimeoutMillis = 3_000L,
                readTimeoutMillis = 3_000L,
                writeTimeoutMillis = 3_000L,
                crashBatchSize = 20,
                crashMaxBatchBytes = 512 * 1024,
                crashUploadIntervalMillis = 30_000L,
            ),
            clock = clock,
            requestIdGenerator = { "request-1" },
            canSend = canSend,
        )
    }

    /** 构造保留精确地址和原始诊断的合成 Native 事件。 */
    private fun nativeEvent(id: String): CrashEvent = sampleEvent(id).copy(crash = CrashPayload(kind = "native",
        nativeCrash = NativeCrash(abi = "arm64-v8a", systemTid = 42,
            signal = NativeSignal(11, "SIGSEGV", 1, faultAddress = "0x0"),
            registers = NativeRegisters(status = "ok", values = mapOf("pc" to "0xffffffffffffffff")),
            modules = emptyList(), backtrace = NativeBacktrace(status = "unavailable", reason = "interrupted")),
        diagnostics = CrashDiagnostics(process = CrashProcess(status = "ok", pid = 123, name = "synthetic"))))


    private fun sampleEvent(eventId: String): CrashEvent {
        return CrashEvent(
            schemaVersion = 2,
            eventId = eventId,
            eventType = "crash",
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
