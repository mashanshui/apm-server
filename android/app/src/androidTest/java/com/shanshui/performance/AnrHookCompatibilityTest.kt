package com.shanshui.performance

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.bytedance.android.bytehook.ByteHook
import com.bytedance.shadowhook.ShadowHook
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** 验证现有 Rhea 与 ANR 将使用的 ByteHook 可共享同一套 ShadowHook。 */
@RunWith(AndroidJUnit4::class)
class AnrHookCompatibilityTest {
    /** 在应用已经启动 Rhea 后初始化 ByteHook，校验实际原生库和运行模式。 */
    @Test
    fun initializesByteHookWithRunningJank() {
        /** 示例 Application 启动时创建的 SDK。 */
        val sdk = PerformanceSdk.current()
        assertNotNull("示例 SDK 未初始化", sdk)
        assertTrue("Rhea 未启动，不能验证共存", sdk!!.isJankAvailable)

        /** 使用自动模式，与 Rhea 所需的 ShadowHook 共享模式共存。 */
        val status = ByteHook.init(
            ByteHook.ConfigBuilder()
                .setMode(ByteHook.Mode.AUTOMATIC)
                .setShadowhookMode(ShadowHook.Mode.SHARED)
                .build(),
        )
        assertEquals("ByteHook 初始化失败", 0, status)
        assertEquals("bytehook version 1.1.1", ByteHook.getVersion())
        assertEquals("shadowhook version 2.0.0", ShadowHook.getVersion())
        assertEquals(ShadowHook.Mode.SHARED, ShadowHook.getMode())
        assertTrue("初始化 ByteHook 后 Rhea 不可用", sdk.isJankAvailable)
    }
}
