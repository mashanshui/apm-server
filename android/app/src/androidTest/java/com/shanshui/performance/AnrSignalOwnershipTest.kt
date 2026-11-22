package com.shanshui.performance

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.shanshui.performance.anr.AnrComponent
import com.shanshui.performance.anr.AnrHandle
import java.io.File
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** 对照 Matrix 在第三方接管信号后的原始关闭行为。 */
@RunWith(AndroidJUnit4::class)
class AnrSignalOwnershipTest {
    /** Matrix 会恢复启动前的处理器，不保留之后安装的第三方处理器。 */
    @Test
    fun closeRestoresOriginalHandlerAfterThirdPartyTakeover() {
        /** 只在专用 runner 和测试注入的原生探针存在时执行。 */
        val application = ApplicationProvider.getApplicationContext<Application>()
        assumeTrue(application.javaClass == Application::class.java)
        val probe = File(application.cacheDir, "libapm_signal_probe.so")
        assumeTrue("需要先注入测试原生探针", probe.isFile)
        System.load(probe.absolutePath)
        /** 在同一进程内保存启动前的处理器地址。 */
        val before = nativeCurrentHandler()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val handleRef = AtomicReference<AnrHandle>()
        instrumentation.runOnMainSync { handleRef.set(AnrComponent.start(application, AnrConfig(enabled = true))) }
        val handle = handleRef.get()
        try {
            assertTrue(handle.isAvailable)
            assertTrue(nativeInstallThirdParty())
            assertTrue(nativeIsThirdPartyInstalled())
        } finally {
            instrumentation.runOnMainSync { handle.close() }
        }
        assertFalse("Matrix 原始关闭不保留后安装的处理器", nativeIsThirdPartyInstalled())
        assertTrue("关闭后应恢复启动前的处理器", nativeCurrentHandler() == before)
    }

    /** 安装测试第三方处理器。 */
    private external fun nativeInstallThirdParty(): Boolean

    /** 查询第三方处理器是否仍被安装。 */
    private external fun nativeIsThirdPartyInstalled(): Boolean

    /** 读取处理器地址，仅用于进程内等值比较。 */
    private external fun nativeCurrentHandler(): Long
}
