package com.shanshui.performance.anr

import com.shanshui.performance.network.NetworkConfig
import com.shanshui.performance.network.TransportSession
import java.nio.file.Files
import java.util.UUID
import java.util.zip.GZIPInputStream
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 验证目标隔离、有效确认、错误退避和实际 gzip 请求。 */
class AnrUploaderTest {
    /** 错误的请求 ID 或不完整计数不能删除事件，稍后可按原内容重试。 */
    @Test
    fun retainsInvalidAcknowledgementAndThenAcceptsDuplicate() {
        val root = Files.createTempDirectory("anr-upload").toFile()
        try {
            val store = AnrCaptureStore(root)
            val event = pending(store, "a")
            var now = 1_000L
            var calls = 0
            val uploader = AnrUploader(store, "a", AnrBatchSender { request ->
                calls++
                assertEquals(event, request.events.single())
                if (calls == 1) AnrSendResult.Success(AnrBatchResponse("wrong", 1, 0, 0, errors = emptyList()))
                else AnrSendResult.Success(AnrBatchResponse(request.requestId, 0, 0, 1, errors = emptyList()))
            }, clock = { now }, requestId = { "request" })
            uploader.flush()
            assertTrue(store.eligible("a", now, 1).isEmpty())
            assertEquals(event, store.eligible("a", Long.MAX_VALUE, 1).single().event)
            assertTrue(store.eligible("b", Long.MAX_VALUE, 1).isEmpty())
            now += 30_000
            uploader.flush()
            assertTrue(store.eligible("a", Long.MAX_VALUE, 1).isEmpty())
            assertEquals(2, calls)
            assertTrue(store.readEvidence(event.eventId) != null)
        } finally {
            root.deleteRecursively()
        }
    }

    /** 单事件批次的重复错误条目不是有效拒绝，必须保留原事件重试。 */
    @Test
    fun retainsEventWhenRejectionContainsDuplicateErrors() {
        /** 使用独立临时目录观察待确认和死信状态。 */
        val root = Files.createTempDirectory("anr-duplicate-errors").toFile()
        try {
            /** 创建一份冻结事件，并伪造两条都指向该事件的拒绝。 */
            val store = AnrCaptureStore(root)
            /** 确认失败时必须保留的原事件。 */
            val original = pending(store, "a")
            /** 故意重复的拒绝说明。 */
            val error = AnrBatchError(index = 0, eventId = original.eventId, code = "INVALID_EVENT")
            /** 使用固定时间验证无效确认后的退避。 */
            val uploader = AnrUploader(store, "a", AnrBatchSender { request ->
                AnrSendResult.Success(AnrBatchResponse(request.requestId, 0, 1, 0, errors = listOf(error, error)))
            }, clock = { 1_000L })
            uploader.flush()
            assertEquals(original, store.eligible("a", Long.MAX_VALUE, 1).single().event)
            assertTrue(store.eligible("a", 1_000L, 1).isEmpty())
            assertFalse(java.io.File(root, "dead-letter/${original.eventId}.json").exists())
        } finally {
            root.deleteRecursively()
        }
    }

    /** 503 保留并按建议退避，403 作为永久拒绝进入死信。 */
    @Test
    fun handlesRetryableAndPermanentHttpFailures() {
        val root = Files.createTempDirectory("anr-errors").toFile()
        try {
            val store = AnrCaptureStore(root)
            val event = pending(store, "a")
            var now = 1_000L
            var calls = 0
            val uploader = AnrUploader(store, "a", AnrBatchSender {
                calls++
                if (calls == 1) AnrSendResult.HttpFailure(503, true, 2) else AnrSendResult.HttpFailure(403, false)
            }, clock = { now })
            uploader.flush()
            now += 1_999
            uploader.flush()
            assertEquals(1, calls)
            now++
            uploader.flush()
            assertEquals(2, calls)
            assertTrue(store.eligible("a", Long.MAX_VALUE, 1).isEmpty())
            assertTrue(java.io.File(root, "dead-letter/${event.eventId}.json").isFile)
        } finally {
            root.deleteRecursively()
        }
    }

    /** 实际 HTTP 的 503 退避和 403 永久拒绝经共享传输正确进入队列状态。 */
    @Test
    fun handlesHttpRetryAndRejectionThroughSharedTransport() {
        /** 仅在本机回环地址提供受控服务。 */
        val server = MockWebServer()
        server.start()
        /** 保存本次测试的独立队列。 */
        val root = Files.createTempDirectory("anr-http-errors").toFile()
        /** 公共传输会话连接测试服务。 */
        val session = TransportSession.create(NetworkConfig(server.url("/").toString(), "test-only-key"))
        try {
            /** 两次响应覆盖建议退避与永久拒绝。 */
            server.enqueue(MockResponse().setResponseCode(503).setHeader("Retry-After", "2")
                .setBody("""{"retryable":true}"""))
            server.enqueue(MockResponse().setResponseCode(403).setBody("""{"retryable":false}"""))
            /** 会话派生的目标摘要。 */
            val target = anrTargetFingerprint(session)
            /** 保存冻结事件的队列。 */
            val store = AnrCaptureStore(root)
            /** 在失败后核对是否改变的原事件。 */
            val original = pending(store, target)
            /** 可推进的墙上时间。 */
            var now = 1_000L
            /** 发送真实 HTTP 的公共传输适配器。 */
            val sender = AnrHttpSender(session.retrofit.create(AnrIngestApi::class.java))
            /** 在受控时间下执行上传。 */
            val uploader = AnrUploader(store, target, sender, clock = { now })
            uploader.flush()
            now += 1_999
            uploader.flush()
            assertEquals(1, server.requestCount)
            assertEquals(original, store.eligible(target, Long.MAX_VALUE, 1).single().event)
            now++
            uploader.flush()
            assertEquals(2, server.requestCount)
            assertTrue(store.eligible(target, Long.MAX_VALUE, 1).isEmpty())
            assertTrue(java.io.File(root, "dead-letter/${original.eventId}.json").isFile)
        } finally {
            session.close()
            server.shutdown()
            root.deleteRecursively()
        }
    }

    /** 响应丢失后保持原载荷，重复确认才删除。 */
    @Test
    fun retriesLostResponseWithTheOriginalEvent() {
        val root = Files.createTempDirectory("anr-response-lost").toFile()
        try {
            val store = AnrCaptureStore(root)
            val original = pending(store, "a")
            var now = 1_000L
            var calls = 0
            val sender = AnrBatchSender { request ->
                assertEquals(original, request.events.single())
                if (++calls == 1) AnrSendResult.Retry else AnrSendResult.Success(
                    AnrBatchResponse(request.requestId, 0, 0, 1, errors = emptyList()))
            }
            val uploader = AnrUploader(store, "a", sender, clock = { now })
            uploader.flush()
            now += 30_000
            uploader.flush()
            assertEquals(2, calls)
            assertTrue(store.eligible("a", Long.MAX_VALUE, 1).isEmpty())
        } finally {
            root.deleteRecursively()
        }
    }

    /** 地址规范化后的相同目标稳定，不同凭据不能复用旧目标。 */
    @Test
    fun bindsTargetToNormalizedUrlAndCredential() {
        val first = TransportSession.create(NetworkConfig("http://127.0.0.1:43111", "key-a"))
        val same = TransportSession.create(NetworkConfig("http://127.0.0.1:43111/", "key-a"))
        val otherKey = TransportSession.create(NetworkConfig("http://127.0.0.1:43111/", "key-b"))
        val otherUrl = TransportSession.create(NetworkConfig("http://127.0.0.1:43112/", "key-a"))
        try {
            assertEquals(anrTargetFingerprint(first), anrTargetFingerprint(same))
            assertFalse(anrTargetFingerprint(first) == anrTargetFingerprint(otherKey))
            assertFalse(anrTargetFingerprint(first) == anrTargetFingerprint(otherUrl))
            assertFalse(anrTargetFingerprint(first).contains("key-a"))
        } finally {
            listOf(first, same, otherKey, otherUrl).forEach { it.close() }
        }
    }

    /** 凭据变更后的会话不能发送旧目标事件，恢复原凭据后仍可上传。 */
    @Test
    fun credentialChangeKeepsPendingEventForOriginalTarget() {
        /** 原服务地址及原测试凭据。 */
        val originalSession = TransportSession.create(NetworkConfig("http://127.0.0.1:43111/", "key-a"))
        /** 同一服务地址及变更后的测试凭据。 */
        val changedSession = TransportSession.create(NetworkConfig("http://127.0.0.1:43111/", "key-b"))
        /** 只供此用例使用的本地队列目录。 */
        val root = Files.createTempDirectory("anr-target-change").toFile()
        try {
            /** 原目标的不可逆摘要。 */
            val originalTarget = anrTargetFingerprint(originalSession)
            /** 变更凭据后的目标摘要。 */
            val changedTarget = anrTargetFingerprint(changedSession)
            /** 存放原目标事件的队列。 */
            val store = AnrCaptureStore(root)
            /** 必须保留原内容的冻结事件。 */
            val original = pending(store, originalTarget)
            /** 验证发送器调用次数。 */
            var calls = 0
            /** 模拟服务端对原目标事件的有效确认。 */
            val sender = AnrBatchSender { request ->
                calls++
                assertEquals(original, request.events.single())
                AnrSendResult.Success(AnrBatchResponse(request.requestId, 1, 0, 0, errors = emptyList()))
            }
            AnrUploader(store, changedTarget, sender, clock = { 1_000L }).flush()
            assertEquals(0, calls)
            assertEquals(original, store.eligible(originalTarget, Long.MAX_VALUE, 1).single().event)
            AnrUploader(store, originalTarget, sender, clock = { 1_000L }).flush()
            assertEquals(1, calls)
            assertTrue(store.eligible(originalTarget, Long.MAX_VALUE, 1).isEmpty())
        } finally {
            originalSession.close()
            changedSession.close()
            root.deleteRecursively()
        }
    }

    /** 新目标刷新时只按保留期清理旧目标，边界内的事件不得被误发或删除。 */
    @Test
    fun expiresOtherTargetsOnlyAfterTheirRetentionPeriod() {
        /** 每个测试使用独立队列及可推进的墙上时间。 */
        val root = Files.createTempDirectory("anr-target-expiration").toFile()
        try {
            /** 旧目标事件和新目标上传器共用队列。 */
            val store = AnrCaptureStore(root)
            /** 保留期边界的原事件。 */
            val original = pending(store, "old-target")
            /** 首次刷新时间恰好落在保留期边界。 */
            var now = ANR_PENDING_RETENTION_MILLIS + original.occurredAt
            /** 检查新目标没有发送旧事件。 */
            var requests = 0
            /** 只处理新目标的上传器。 */
            val uploader = AnrUploader(store, "new-target", AnrBatchSender {
                requests++
                AnrSendResult.Retry
            }, clock = { now })
            uploader.flush()
            assertEquals(original, store.eligible("old-target", Long.MAX_VALUE, 1).single().event)
            assertEquals(0, requests)
            now++
            uploader.flush()
            assertTrue(store.eligible("old-target", Long.MAX_VALUE, 1).isEmpty())
            assertEquals(0, requests)
        } finally {
            root.deleteRecursively()
        }
    }

    /** 共享会话发送 gzip 批次且凭据只出现在请求头。 */
    @Test
    fun sendsGzipThroughBorrowedTransportSession() {
        val server = MockWebServer()
        server.start()
        val root = Files.createTempDirectory("anr-http").toFile()
        val key = "test-only-key"
        val session = TransportSession.create(NetworkConfig(server.url("/").toString(), key))
        try {
            val target = anrTargetFingerprint(session)
            val store = AnrCaptureStore(root)
            pending(store, target)
            server.enqueue(MockResponse().setResponseCode(200).setHeader("Content-Type", "application/json")
                .setBody("""{"requestId":"request","accepted":1,"rejected":0,"duplicate":0,"retryable":false,"errors":[]}"""))
            val sender = AnrHttpSender(session.retrofit.create(AnrIngestApi::class.java))
            AnrUploader(store, target, sender, clock = { 1_000L }, requestId = { "request" }).flush()
            val request = server.takeRequest()
            assertEquals("gzip", request.getHeader("Content-Encoding"))
            assertEquals(key, request.getHeader("X-App-Key"))
            assertEquals("2", request.getHeader("X-Schema-Version"))
            val body = GZIPInputStream(request.body.inputStream()).bufferedReader().readText()
            assertTrue(body.contains("\"eventType\":\"anr\""))
            assertFalse(body.contains(key))
            assertTrue(store.eligible(target, Long.MAX_VALUE, 1).isEmpty())
        } finally {
            session.close()
            server.shutdown()
            root.deleteRecursively()
        }
    }

    /** 持久化一条处于原目标的有效冻结事件。 */
    private fun pending(store: AnrCaptureStore, target: String): AnrUploadEvent {
        val id = UUID.randomUUID().toString()
        val identity = AnrReportIdentity("device", UUID.randomUUID().toString(), UUID.randomUUID().toString(),
            "com.example.app", "1.0", 1, "1.0-1", "debug", "test")
        val report = AnrReport(id, 100, AnrReportType.SIGNAL_ANR, identity, captureId = id,
            javaStack = "sample.main(Main.kt:1)", fromProcessErrorState = false)
        assertTrue(store.begin(AnrCaptureRecord(report, target, "35", "model")))
        assertTrue(store.freeze(id))
        return store.eligible(target, Long.MAX_VALUE, 1).single().event
    }
}
