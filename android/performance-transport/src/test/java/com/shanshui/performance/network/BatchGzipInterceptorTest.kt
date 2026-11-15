package com.shanshui.performance.network

import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.MockResponse
import org.junit.Assert.*
import org.junit.Test
import java.util.zip.GZIPInputStream

/** 通过真实 HTTP 检查目标匹配与线上 gzip 字节。 */
class BatchGzipInterceptorTest {
    /** 已有 gzip 的下游网络失败不得误记为压缩准备失败。 */
    @Test fun existingGzipNetworkFailureDoesNotLogPreparationFailure() {
        /** 捕获安全摘要。 */
        val logs = mutableListOf<String>()
        /** 隔离测试端点，不发起实际连接。 */
        val endpoint = okhttp3.HttpUrl.Builder().scheme("http").host("localhost").addPathSegments("ingest/v1/batches").build()
        /** 下游固定失败的调用链，模拟已经进入网络执行阶段。 */
        val client = okhttp3.OkHttpClient.Builder()
            .addInterceptor(BatchGzipInterceptor(endpoint, true, logs::add))
            .addInterceptor { throw java.io.IOException("downstream-network") }.build()
        /** 已编码的合法 gzip 正文。 */
        val output = java.io.ByteArrayOutputStream()
        java.util.zip.GZIPOutputStream(output).use { it.write("{}".toByteArray()) }
        try {
            /** 上层应收到原网络错误。 */
            val failure = assertThrows(java.io.IOException::class.java) {
                client.newCall(Request.Builder().url(endpoint).header("Content-Encoding", "gzip")
                    .post(output.toByteArray().toRequestBody("application/json".toMediaType())).build()).execute()
            }
            assertEquals("downstream-network", failure.message)
            assertTrue(logs.isEmpty())
        } finally {
            client.dispatcher.executorService.shutdown()
            client.connectionPool.evictAll()
        }
    }
    /** 小批次、Unicode 及前缀路径均压缩；其他路径和类型保持原正文。 */
    @Test fun routesAndBytesMatchContract() {
        /** 隔离 HTTP 服务。 */
        val server = MockWebServer()
        server.start()
        /** SDK 自建共享会话。 */
        val session = TransportSession.create(NetworkConfig(server.url("/apm/").toString(), "synthetic-key"))
        try {
            for ((path, type, method, gzip) in listOf(
                listOf("/apm/ingest/v1/batches", "application/json", "POST", "yes"),
                listOf("/ingest/v1/batches", "application/json", "POST", "no"),
                listOf("/apm/ingest/v1/batches", "application/json", "PUT", "no"),
                listOf("/apm/ingest/v1/batches", "multipart/form-data", "POST", "no"),
                listOf("/apm/ingest/v1/stack-artifacts:parse", "application/zip", "POST", "no"),
            )) {
                /** 原始测试正文。 */
                val raw = "{\"文本\":\"你好\"}"
                server.enqueue(MockResponse().setBody("{}"))
                session.okHttpClient.newCall(Request.Builder().url(server.url(path))
                    .method(method, raw.toRequestBody(type.toMediaType())).build()).execute().close()
                /** 实际线上请求。 */
                val received = server.takeRequest()
                if (gzip == "yes") {
                    assertEquals("gzip", received.getHeader("Content-Encoding"))
                    assertEquals(received.bodySize.toString(), received.getHeader("Content-Length"))
                    assertEquals(raw, GZIPInputStream(received.body.inputStream()).reader().readText())
                } else {
                    assertNull(received.getHeader("Content-Encoding"))
                    assertEquals(raw, received.body.readUtf8())
                }
            }
        } finally {
            session.close()
            server.shutdown()
        }
    }
    /** 验证长度头清理、膨胀及最高原始预算，避免只测试可压缩大正文。 */
    @Test fun sizesAndPreparationFailures() {
        /** 隔离 HTTP 端点。 */
        val server = MockWebServer()
        server.start()
        /** 传输会话。 */
        val session = TransportSession.create(NetworkConfig(server.url("/").toString(), "test-key"))
        try {
            for (raw in listOf("{}", "{\"text\":\"中文\"}", " ".repeat(1024 * 1024))) {
                server.enqueue(MockResponse().setBody("{}"))
                session.okHttpClient.newCall(Request.Builder().url(server.url("/ingest/v1/batches"))
                    .header("Content-Length", "9999999")
                    .post(raw.toRequestBody("application/json".toMediaType())).build()).execute().close()
                /** 捕获的 gzip 请求。 */
                val captured = server.takeRequest()
                assertEquals(captured.bodySize.toString(), captured.getHeader("Content-Length"))
                if (raw == "{}") assertTrue(captured.bodySize > raw.length)
                assertEquals(raw, GZIPInputStream(captured.body.inputStream()).reader().readText())
            }
            assertThrows(java.io.IOException::class.java) {
                session.okHttpClient.newCall(Request.Builder().url(server.url("/ingest/v1/batches"))
                    .post(" ".repeat(1024 * 1024 + 1).toRequestBody("application/json".toMediaType()))
                    .build()).execute()
            }
            assertNull(server.takeRequest(100, java.util.concurrent.TimeUnit.MILLISECONDS))
        } finally { session.close(); server.shutdown() }
    }

    /** 已有 gzip 不二次编码，其他编码在发送前失败。 */
    @Test fun existingEncodingIsPreserved() {
        /** 隔离服务。 */
        val server = MockWebServer()
        server.start()
        /** 传输会话。 */
        val session = TransportSession.create(NetworkConfig(server.url("/").toString(), "test-key"))
        /** 合法 gzip 字节。 */
        val output = java.io.ByteArrayOutputStream()
        java.util.zip.GZIPOutputStream(output).use { it.write("{}".toByteArray()) }
        try {
            server.enqueue(MockResponse().setBody("{}"))
            session.okHttpClient.newCall(Request.Builder().url(server.url("/ingest/v1/batches"))
                .header("Content-Encoding", "gzip")
                .post(output.toByteArray().toRequestBody("application/json".toMediaType())).build()).execute().close()
            assertArrayEquals(output.toByteArray(), server.takeRequest().body.readByteArray())
            assertThrows(java.io.IOException::class.java) {
                session.okHttpClient.newCall(Request.Builder().url(server.url("/ingest/v1/batches"))
                    .header("Content-Encoding", "br")
                    .post("{}".toRequestBody("application/json".toMediaType())).build()).execute()
            }
            assertNull(server.takeRequest(100, java.util.concurrent.TimeUnit.MILLISECONDS))
        } finally { session.close(); server.shutdown() }
    }

    /** 失败正文不能发出 HTTP，成功日志仅包含安全摘要。 */
    @Test fun logsAndIoFailureStaySafe() {
        /** 隔离服务。 */
        val server = MockWebServer()
        server.start()
        /** 捕获日志。 */
        val logs = mutableListOf<String>()
        /** 只测试真实拦截器的客户端。 */
        val client = okhttp3.OkHttpClient.Builder().addInterceptor(
            BatchGzipInterceptor(server.url("/ingest/v1/batches"), true, logs::add)).build()
        try {
            server.enqueue(MockResponse().setBody("{}"))
            client.newCall(Request.Builder().url(server.url("/ingest/v1/batches"))
                .post("{\"secret\":true}".toRequestBody("application/json".toMediaType())).build()).execute().close()
            server.takeRequest()
            assertTrue(logs.single().contains("rawBytes="))
            assertTrue(logs.single().contains("elapsedNs="))
            assertFalse(logs.single().contains("secret"))
            /** 在写入正文时失败的请求体。 */
            val broken = object : okhttp3.RequestBody() {
                /** 保持目标媒体类型。 */
                override fun contentType() = "application/json".toMediaType()
                /** 模拟读取本地正文失败。 */
                override fun writeTo(sink: okio.BufferedSink) { throw java.io.IOException("private-secret") }
            }
            assertThrows(java.io.IOException::class.java) {
                client.newCall(Request.Builder().url(server.url("/ingest/v1/batches")).post(broken).build()).execute()
            }
            assertFalse(logs.last().contains("private-secret"))
            assertTrue(logs.last().contains("stage=prepare"))
            /** 关闭日志的同策略客户端。 */
            val silent = client.newBuilder().apply { interceptors().clear() }
                .addInterceptor(BatchGzipInterceptor(server.url("/ingest/v1/batches"), false, logs::add)).build()
            /** 关闭前的日志条数。 */
            val count = logs.size
            assertThrows(java.io.IOException::class.java) {
                silent.newCall(Request.Builder().url(server.url("/ingest/v1/batches")).post(broken).build()).execute()
            }
            assertEquals(count, logs.size)
            assertNull(server.takeRequest(100, java.util.concurrent.TimeUnit.MILLISECONDS))
        } finally {
            client.dispatcher.executorService.shutdown()
            client.connectionPool.evictAll()
            server.shutdown()
        }
    }

    /** 使用实际拦截器记录合成样本开销，不将噪声样本视为合法业务事件。 */
    @Test fun measuresSyntheticBatches() {
        /** 隔离接收端。 */
        val server = MockWebServer()
        server.start()
        /** 实际压缩摘要，不包含原始正文。 */
        val logs = mutableListOf<String>()
        /** 使用生产拦截器的客户端。 */
        val client = okhttp3.OkHttpClient.Builder().addInterceptor(
            BatchGzipInterceptor(server.url("/ingest/v1/batches"), true, logs::add)).build()
        try {
            for (sample in listOf("{}".toByteArray(),
                ("{\"stack\":[" + List(1000) { "\"com.example.Main.run(Main.java:100)\"" }.joinToString(",") + "]}").toByteArray(),
                ByteArray(1024 * 1024).also { java.util.Random(42).nextBytes(it) })) {
                server.enqueue(MockResponse().setBody("{}"))
                client.newCall(Request.Builder().url(server.url("/ingest/v1/batches"))
                    .post(sample.toRequestBody("application/json".toMediaType())).build()).execute().close()
                server.takeRequest()
                println(logs.last())
            }
        } finally {
            client.dispatcher.executorService.shutdown()
            client.connectionPool.evictAll()
            server.shutdown()
        }
    }
}
