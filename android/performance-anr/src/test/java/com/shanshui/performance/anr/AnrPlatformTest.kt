package com.shanshui.performance.anr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 验证采集平台判断，不需要 Android 设备或加载原生库。 */
class AnrPlatformTest {
    /** 默认配置及其他条件不足时不会进入支持状态。 */
    @Test
    fun rejectsDisabledAndUnsupportedProcesses() {
        assertEquals(AnrInitStatus.DISABLED, AnrPlatform.check(AnrConfig(), 35, true, true, "arm64-v8a")?.status)
        assertEquals(AnrInitStatus.UNSUPPORTED_API, AnrPlatform.check(AnrConfig(true), 22, true, true, "arm64-v8a")?.status)
        assertEquals(AnrInitStatus.NOT_MAIN_PROCESS, AnrPlatform.check(AnrConfig(true), 35, false, true, "arm64-v8a")?.status)
        assertEquals(AnrInitStatus.UNSUPPORTED_ABI, AnrPlatform.check(AnrConfig(true), 35, true, false, "arm64-v8a")?.status)
        assertEquals(AnrInitStatus.UNSUPPORTED_ABI, AnrPlatform.check(AnrConfig(true), 35, true, true, "x86_64")?.status)
        assertEquals(AnrInitStatus.UNSUPPORTED_ABI, AnrPlatform.check(AnrConfig(true), 35, true, true, null)?.status)
    }

    /** 支持结果只表示可尝试初始化，未直接声称信号采集已启动。 */
    @Test
    fun acceptsSupportedPlatformWithoutClaimingReady() {
        assertNull(AnrPlatform.check(AnrConfig(true), 23, true, true, "arm64-v8a"))
        assertNull(AnrPlatform.check(AnrConfig(true), 37, true, true, "arm64-v8a"))
    }
}
