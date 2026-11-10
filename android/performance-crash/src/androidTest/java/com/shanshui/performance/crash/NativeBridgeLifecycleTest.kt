package com.shanshui.performance.crash

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/** 验证 Native 初始化回滚、重复启停和信号处理权。 */
@RunWith(AndroidJUnit4::class)
class NativeBridgeLifecycleTest {
    /** 仅使用仪器测试自己的私有缓存。 */
    private val context = ApplicationProvider.getApplicationContext<Context>()

    /** 每次测试恢复桥与模拟第三方的原状态。 */
    @After
    fun cleanup() {
        NativeCrashBridge.uninstall()
        NativeSignalProbe.nativeRestoreRememberedHandler()
    }

    /** 同一核心重复安装幂等，不允许另一实例夺取所有权，关闭后可以重新安装。 */
    @Test
    fun repeatsInstallAndReleaseWithoutTakingAnotherOwnersFile() {
        val first = file("first")
        val second = file("second")
        assertTrue(NativeCrashBridge.install(first))
        assertFalse(NativeCrashBridge.captureInProgress())
        assertTrue(NativeCrashBridge.install(first))
        assertFalse(NativeCrashBridge.install(second))
        assertFalse(second.exists())
        NativeCrashBridge.uninstall()
        assertFalse(first.exists())
        assertTrue(NativeCrashBridge.install(second))
        NativeCrashBridge.uninstall()
        assertFalse(second.exists())
    }

    /** 独立程序不可用时回滚新核心，不删除其他文件或阻止后续重新初始化。 */
    @Test
    fun rollsBackUnavailableDumperWithoutDeletingExistingFiles() {
        val core = file("rollback")
        val trace = file("trace")
        val diagnostics = file("diagnostics")
        trace.writeText("sentinel")
        val missing = File(context.applicationInfo.nativeLibraryDir, "libapm_missing_for_test.so")
        assertFalse(NativeCrashBridge.install(core, missing, trace, diagnostics))
        assertFalse(core.exists())
        assertTrue(trace.readText() == "sentinel")
        assertFalse(diagnostics.exists())
        assertTrue(NativeCrashBridge.install(core))
        NativeCrashBridge.uninstall()
        trace.delete()
    }

    /** 后来注册的第三方处理器不能被本模块关闭时覆盖。 */
    @Test
    fun leavesLaterThirdPartyHandlerUntouched() {
        val core = file("third-party")
        assertTrue(NativeCrashBridge.protocolVersion() == 1)
        assertTrue(NativeSignalProbe.nativeRememberHandler())
        assertTrue(NativeCrashBridge.install(core))
        assertTrue(NativeSignalProbe.nativeInstallThirdPartyHandler())
        NativeCrashBridge.uninstall()
        assertTrue(NativeSignalProbe.nativeHasThirdPartyHandler())
    }

    /** 为每次测试创建互不冲突的临时文件名。 */
    private fun file(label: String): File = File(context.cacheDir, "native-$label-${UUID.randomUUID()}")
}
