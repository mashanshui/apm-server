package com.shanshui.performance.crash

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CrashEventFactoryTest {
    /** 私有路径和包含多项凭据的请求头不能进入本地持久材料。 */
    @Test
    fun redactsPrivatePathsAndWholeCredentialHeaders() {
        val input = "path=/data/user/0/com.example/files/private sentinel.txt\n" +
            "Cookie: session=secret-one; other=secret-two\n" +
            "Authorization: Bearer secret-three\n" +
            "X-App-Key: apm_ak_AbCdefGHijkLmnoPQRstuVwXYZ\n" +
            "standalone apm_qt_AbCdefGHijkLmnoPQRstuVwXYZ\n" +
            "reference https://example.invalid/#secret-fragment"
        val sanitized = CrashSanitizer.sanitize(input, 4096)
        assertFalse(sanitized.contains("sentinel"))
        assertFalse(sanitized.contains("secret-one"))
        assertFalse(sanitized.contains("secret-two"))
        assertFalse(sanitized.contains("secret-three"))
        assertFalse(sanitized.contains("secret-fragment"))
        assertFalse(sanitized.contains("apm_ak_AbCdefGHijkLmnoPQRstuVwXYZ"))
        assertFalse(sanitized.contains("apm_qt_AbCdefGHijkLmnoPQRstuVwXYZ"))
    }

    @Test
    fun crashEventSanitizesMessageAndLimitsThrowablePayload() {
        val factory = CrashEventFactory(
            config = testConfig(),
            serviceConfig = testServiceConfig(),
            sessionId = "session-1",
            processId = "11111111-1111-4111-8111-111111111111",
            anonymousDeviceId = "install-1",
            deviceInfo = CrashDeviceInfo("35", "Pixel Test"),
            networkTypeProvider = { "wifi" },
            clock = { 1_726_000_000_000 },
            idGenerator = { "event-1" },
        )
        val root = IllegalStateException(
            "contact alice@example.com, token=secret-value, " +
                "https://example.test/path?user=alice&token=secret",
        )
        root.stackTrace = Array(220) { index ->
            StackTraceElement(
                "com.shanshui.performance.Screen$index",
                "render",
                "Screen.kt",
                index,
            )
        }

        val event = factory.crash(root)
        val crash = event.crash

        assertEquals("crash", event.eventType)
        assertEquals(2, event.schemaVersion)
        assertEquals("com.shanshui.performance", event.packageName)
        assertEquals(1_726_000_000_000, event.occurredAt)
        assertEquals("wifi", event.networkType)
        assertNotNull(crash)
        assertTrue(crash!!.fatal)
        assertEquals("jvm", crash.kind)
        val chain = requireNotNull(crash.throwableChain)
        assertTrue(chain.size <= 16)
        assertTrue(chain.sumOf { it.frames.size } <= 200)
        assertFalse(chain.first().message.orEmpty().contains("alice@example.com"))
        assertFalse(chain.first().message.orEmpty().contains("secret-value"))
        assertFalse(chain.first().message.orEmpty().contains("https://"))
        assertTrue(chain.first().frames.first().applicationFrame == true)
    }

    @Test
    fun appStartEventHasNoCrashPayloadAndUsesCommonSessionMetadata() {
        val factory = CrashEventFactory(
            config = testConfig(),
            serviceConfig = testServiceConfig(),
            sessionId = "session-1",
            processId = "11111111-1111-4111-8111-111111111111",
            anonymousDeviceId = "install-1",
            deviceInfo = CrashDeviceInfo("35", "Pixel Test"),
            networkTypeProvider = { null },
            clock = { 123L },
            idGenerator = { "start-1" },
        )

        val event = factory.appStart()

        assertEquals("app_start", event.eventType)
        assertEquals("session-1", event.sessionId)
        assertEquals("11111111-1111-4111-8111-111111111111", event.processId)
        assertEquals("install-1", event.anonymousDeviceId)
        assertEquals(123L, event.occurredAt)
        assertTrue(event.crash == null)
    }

    private fun testConfig(): CrashReporterConfig {
        return CrashReporterConfig(
            packageName = "com.shanshui.performance",
            appVersion = "1.0",
            versionCode = 1,
            buildId = "build-1",
            applicationPackage = "com.shanshui.performance",
        )
    }

    private fun testServiceConfig(): CrashServiceConfig {
        return CrashServiceConfig(
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
        )
    }
}
