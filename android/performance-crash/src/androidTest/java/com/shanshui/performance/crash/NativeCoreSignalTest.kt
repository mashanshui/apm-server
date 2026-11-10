package com.shanshui.performance.crash

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/** 在隔离子进程验证真实致命信号的有限核心提交。 */
@RunWith(AndroidJUnit4::class)
class NativeCoreSignalTest {
    /** 使用测试应用私有缓存，不接触已有 Crash 队列。 */
    private val directory: File = ApplicationProvider.getApplicationContext<Context>().cacheDir

    /** 测试后释放本进程信号处理权。 */
    @After
    fun cleanup() {
        NativeCrashBridge.uninstall()
    }

    /** 同步空指针 SIGSEGV 保留实际发生时间、线程和零故障地址。 */
    @Test
    fun savesSegvCoreWithoutJavaCallback() {
        val core = File(directory, "native-core-${UUID.randomUUID()}.bin")
        val startedAt = System.currentTimeMillis()
        assertTrue(NativeCrashBridge.install(core))
        NativeSignalProbe.nativeForkCrash(11)
        val decoded = NativeCoreCodec.decode(core.readBytes())
        assertNotNull(decoded)
        assertEquals(11, decoded!!.signal)
        assertEquals("0x0", decoded.faultAddress)
        assertTrue(decoded.occurredAt >= startedAt)
        assertTrue(decoded.pid > 0 && decoded.systemTid > 0)
        assertTrue(decoded.registers.isNotEmpty())
        core.delete()
    }

    /** 主动 SIGABRT 不伪造故障地址，也不依赖 Java 回调。 */
    @Test
    fun savesAbortCoreWithoutFaultAddress() {
        val core = File(directory, "native-core-${UUID.randomUUID()}.bin")
        assertTrue(NativeCrashBridge.install(core))
        NativeSignalProbe.nativeForkCrash(6)
        val decoded = requireNotNull(NativeCoreCodec.decode(core.readBytes()))
        assertEquals(6, decoded.signal)
        assertEquals(null, decoded.faultAddress)
        assertTrue(decoded.registers.isNotEmpty())
        core.delete()
    }
}

/** Debug 原生库里的隔离进程信号触发入口。 */
internal object NativeSignalProbe {
    /** 返回隔离进程的终止状态，不在测试进程内抛出 JVM 异常。 */
    external fun nativeForkCrash(signal: Int): Int

    /** 在隔离进程当前线程触发致命信号。 */
    external fun nativeCrashInCurrentThread(signal: Int)

    /** 在不附着 JVM 的纯 Native 线程触发致命信号。 */
    external fun nativeCrashInNativeThread()

    /** 在有限等待的隔离子进程启用 dumper 后触发真实信号。 */
    external fun nativeForkDumperCrash(dumperPath: String, tracePath: String, nativeThread: Boolean, hangDumper: Boolean,
                                      hangCallback: Boolean = false): Int

    /** 仅配置隔离设备测试使用的信号和合成 abort 消息。 */
    external fun nativeConfigureSignal(signal: Int, message: String?)

    /** 保存测试前的 SIGSEGV 处理权。 */ external fun nativeRememberHandler(): Boolean
    /** 模拟后注册的第三方处理器。 */ external fun nativeInstallThirdPartyHandler(): Boolean
    /** 检查第三方仍持有处理权。 */ external fun nativeHasThirdPartyHandler(): Boolean
    /** 将测试环境恢复至原处理器。 */ external fun nativeRestoreRememberedHandler()
}
