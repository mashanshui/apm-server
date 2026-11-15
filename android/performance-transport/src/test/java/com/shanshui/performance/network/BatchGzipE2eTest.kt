package com.shanshui.performance.network

import okhttp3.Request
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** 由隔离 Servlet 测试传入临时入口与合成凭据，验证实际 SDK 到服务端。 */
class BatchGzipE2eTest {
    /** SDK 真实自建会话穿过代理，验证接受与重复确认。 */
    @Test fun sdkReachesIsolatedBackend() {
        /** 独立测试入口，默认不连接任何已有环境。 */
        val base = System.getenv("APM_GZIP_E2E_URL")
        assumeTrue(base != null)
        /** 本次合成测试凭据。 */
        val key = requireNotNull(System.getenv("APM_GZIP_E2E_KEY"))
        /** 固定合成事件，重试保持字节和身份。 */
        val json = requireNotNull(System.getenv("APM_GZIP_E2E_BODY"))
        /** 与生产 SDK 一致的自建会话。 */
        val session = TransportSession.create(NetworkConfig(requireNotNull(base), key))
        try {
            for (expected in listOf("\"accepted\":1", "\"duplicate\":1")) {
                session.okHttpClient.newCall(Request.Builder().url(base + "ingest/v1/batches")
                    .post(json.toRequestBody("application/json".toMediaType())).build()).execute().use {
                    assertEquals(200, it.code)
                    assertTrue(requireNotNull(it.body).string().contains(expected))
                }
            }
            /** 不可压缩合成噪声只用于大小边界，不代表合法事件 JSON。 */
            val boundary = ByteArray(1024 * 1024).also { java.util.Random(42).nextBytes(it) }
            session.okHttpClient.newCall(Request.Builder().url(base + "ingest/v1/batches")
                .post(boundary.toRequestBody("application/json".toMediaType())).build()).execute().use {
                assertEquals(413, it.code)
            }
        } finally { session.close() }
    }
}
