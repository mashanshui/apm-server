package com.shanshui.performance.network

import com.google.gson.Gson
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.MockResponse
import org.junit.Assert.*
import org.junit.Test
import java.util.zip.GZIPInputStream

/** 通过实际领域 Retrofit API 验证共享 gzip，不替换业务发送函数。 */
class MetricsGzipApiTest {
    /** 真实序列化后的线上正文必须保持原事件类型和载荷。 */
    @Test fun domainApisUseGzip() = runBlocking {
        /** 隔离 HTTP 接收端。 */
        val server = MockWebServer()
        server.start()
        /** 全量与按需工厂共同使用的自建会话。 */
        val session = TransportSession.create(NetworkConfig(server.url("/").toString(), "synthetic-key"))
        try {
            server.enqueue(MockResponse().setBody("{}"))
            /** 第 0 种实际领域请求。 */
            val request0 = Gson().fromJson("""{"requestId":"r","events":[{"schemaVersion":2,"eventId":"gzip-event","eventType":"frame_scene_summary","occurredAt":1,"sessionId":"s","processId":"11111111-1111-4111-8111-111111111111","anonymousDeviceId":"d","packageName":"com.example.app","appVersion":"1.0","versionCode":1,"buildId":"build","environment":"test","channel":"test","osVersion":"35","deviceModel":"synthetic","frameSceneSummary":{"scene":"Main","algorithmVersion":"fps-v1","activeDurationMs":1000,"uiRefreshFrameCount":60,"refreshRateHz":60,"normalizedFps60":60}}]}""", FpsBatchRequest::class.java)
            session.retrofit.create(FpsIngestApi::class.java).ingest(2, request0)
            /** 当前线上 gzip。 */
            val captured0 = server.takeRequest()
            assertEquals("gzip", captured0.getHeader("Content-Encoding"))
            assertEquals("synthetic-key", captured0.getHeader("X-App-Key"))
            assertEquals(Gson().toJsonTree(request0), com.google.gson.JsonParser.parseString(
                GZIPInputStream(captured0.body.inputStream()).reader().readText()))
            server.enqueue(MockResponse().setBody("{}"))
            /** 第 1 种实际领域请求。 */
            val request1 = Gson().fromJson("""{"requestId":"r","events":[{"schemaVersion":2,"eventId":"gzip-event","eventType":"memory_peak_summary","occurredAt":1,"sessionId":"s","processId":"11111111-1111-4111-8111-111111111111","anonymousDeviceId":"d","packageName":"com.example.app","appVersion":"1.0","versionCode":1,"buildId":"build","environment":"test","channel":"test","osVersion":"35","deviceModel":"synthetic","memoryPeakSummary":{"processName":"p","collectionStartedAt":1,"lastSampledAt":1,"pss":{"bytes":100,"occurredAt":1,"foreground":true}}}]}""", MemoryBatchRequest::class.java)
            session.retrofit.create(MemoryIngestApi::class.java).ingest(2, request1)
            /** 当前线上 gzip。 */
            val captured1 = server.takeRequest()
            assertEquals("gzip", captured1.getHeader("Content-Encoding"))
            assertEquals("synthetic-key", captured1.getHeader("X-App-Key"))
            assertEquals(Gson().toJsonTree(request1), com.google.gson.JsonParser.parseString(
                GZIPInputStream(captured1.body.inputStream()).reader().readText()))
        } finally { session.close(); server.shutdown() }
    }
}
