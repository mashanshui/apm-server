package com.shanshui.performance.network

import com.google.gson.Gson
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.MockResponse
import org.junit.Assert.*
import org.junit.Test
import java.util.zip.GZIPInputStream

/** 通过实际领域 Retrofit API 验证共享 gzip，不替换业务发送函数。 */
class CrashGzipApiTest {
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
            val request0 = Gson().fromJson("""{"requestId":"r","events":[{"schemaVersion":2,"eventId":"gzip-event","eventType":"app_start","occurredAt":1,"sessionId":"s","processId":"11111111-1111-4111-8111-111111111111","anonymousDeviceId":"d","packageName":"com.example.app","appVersion":"1.0","versionCode":1,"buildId":"build","environment":"test","channel":"test","osVersion":"35","deviceModel":"synthetic"}]}""", CrashBatchRequest::class.java)
            session.retrofit.create(CrashIngestApi::class.java).ingest(2, request0)
            /** 当前线上 gzip。 */
            val captured0 = server.takeRequest()
            assertEquals("gzip", captured0.getHeader("Content-Encoding"))
            assertEquals("synthetic-key", captured0.getHeader("X-App-Key"))
            assertEquals(Gson().toJsonTree(request0), com.google.gson.JsonParser.parseString(
                GZIPInputStream(captured0.body.inputStream()).reader().readText()))
            server.enqueue(MockResponse().setBody("{}"))
            /** 第 1 种实际领域请求。 */
            val request1 = Gson().fromJson("""{"requestId":"r","events":[{"schemaVersion":2,"eventId":"gzip-event","eventType":"crash","occurredAt":1,"sessionId":"s","processId":"11111111-1111-4111-8111-111111111111","anonymousDeviceId":"d","packageName":"com.example.app","appVersion":"1.0","versionCode":1,"buildId":"build","environment":"test","channel":"test","osVersion":"35","deviceModel":"synthetic","crash":{"kind":"jvm","fatal":true,"throwableChain":[{"type":"Exception","frames":[]}]}}]}""", CrashBatchRequest::class.java)
            session.retrofit.create(CrashIngestApi::class.java).ingest(2, request1)
            /** 当前线上 gzip。 */
            val captured1 = server.takeRequest()
            assertEquals("gzip", captured1.getHeader("Content-Encoding"))
            assertEquals("synthetic-key", captured1.getHeader("X-App-Key"))
            assertEquals(Gson().toJsonTree(request1), com.google.gson.JsonParser.parseString(
                GZIPInputStream(captured1.body.inputStream()).reader().readText()))
            server.enqueue(MockResponse().setBody("{}"))
            /** 第 2 种实际领域请求。 */
            val request2 = Gson().fromJson("""{"requestId":"r","events":[{"schemaVersion":2,"eventId":"gzip-event","eventType":"crash","occurredAt":1,"sessionId":"s","processId":"11111111-1111-4111-8111-111111111111","anonymousDeviceId":"d","packageName":"com.example.app","appVersion":"1.0","versionCode":1,"buildId":"build","environment":"test","channel":"test","osVersion":"35","deviceModel":"synthetic","crash":{"kind":"native","fatal":true,"nativeCrash":{"abi":"arm64-v8a","systemTid":1,"signal":{"number":11,"name":"SIGSEGV","code":1},"registers":{"status":"unavailable"},"modules":[],"backtrace":{"status":"unavailable","frames":[]}}}}]}""", CrashBatchRequest::class.java)
            session.retrofit.create(CrashIngestApi::class.java).ingest(2, request2)
            /** 当前线上 gzip。 */
            val captured2 = server.takeRequest()
            assertEquals("gzip", captured2.getHeader("Content-Encoding"))
            assertEquals("synthetic-key", captured2.getHeader("X-App-Key"))
            assertEquals(Gson().toJsonTree(request2), com.google.gson.JsonParser.parseString(
                GZIPInputStream(captured2.body.inputStream()).reader().readText()))
        } finally { session.close(); server.shutdown() }
    }
}
