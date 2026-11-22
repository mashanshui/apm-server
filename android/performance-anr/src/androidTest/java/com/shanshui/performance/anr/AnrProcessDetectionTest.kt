package com.shanshui.performance.anr

import android.app.Application
import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.shanshui.performance.identity.RuntimeIdentityProvider
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** 使用真实主进程与独立测试子进程验证平台判断。 */
@RunWith(AndroidJUnit4::class)
class AnrProcessDetectionTest {
    /** 主进程允许进入采集初始化，子进程拒绝采集且不加载 ANR JNI。 */
    @Test
    fun distinguishesMainAndSecondaryProcesses() {
        assumeTrue(Build.VERSION.SDK_INT >= 23 && android.os.Process.is64Bit())
        assumeTrue(Build.SUPPORTED_64_BIT_ABIS.firstOrNull() == "arm64-v8a")
        /** 当前仪器测试的宿主应用。 */
        val application = ApplicationProvider.getApplicationContext<Application>()
        assertNull(AnrPlatform.check(application, AnrConfig(enabled = true)))
        /** ANR 与其他能力共享的主进程身份必须满足项目的身份约定。 */
        val identity = RuntimeIdentityProvider.current(application)
        assertEquals("主进程的 processId 必须与 sessionId 相同", identity.sessionId, identity.processId)
        /** 使用一次性文件名接收同一应用子进程的状态。 */
        val name = "anr-process-${UUID.randomUUID()}"
        val resultFile = File(application.cacheDir, name)
        val connected = CountDownLatch(1)
        val connection = object : ServiceConnection {
            /** 子进程写入结果后通知测试线程。 */
            override fun onServiceConnected(component: ComponentName, binder: IBinder) {
                connected.countDown()
            }

            /** 测试不依赖连接断开的回调。 */
            override fun onServiceDisconnected(component: ComponentName) = Unit
        }
        /** 只绑定测试 APK 中声明的非导出服务。 */
        val intent = Intent(application, AnrSecondaryProcessService::class.java).putExtra("result", name)
        assertTrue(application.bindService(intent, connection, Context.BIND_AUTO_CREATE))
        try {
            assertTrue("子进程未在规定时间内启动", connected.await(15, TimeUnit.SECONDS))
            assertEquals("NOT_MAIN_PROCESS|false|false", resultFile.readText())
        } finally {
            application.unbindService(connection)
            resultFile.delete()
        }
    }
}

/** 只在测试子进程中检查初始化结果和 JNI 加载状态。 */
class AnrSecondaryProcessService : Service() {
    /** 将子进程测试结果写入本测试应用的私有缓存目录。 */
    override fun onBind(intent: Intent): IBinder {
        /** 测试线程生成的一次性结果文件名。 */
        val name = requireNotNull(intent.getStringExtra("result"))
        /** 独立进程必须安全降级，不能启动 Matrix 或加载 JNI。 */
        val handle = AnrComponent.start(application, AnrConfig(enabled = true))
        val loaded = File("/proc/self/maps").useLines { lines -> lines.any { it.contains("libapm_anr.so") } }
        /** 子进程使用独立的 processId，不复用其 sessionId。 */
        val identity = RuntimeIdentityProvider.current(application)
        File(cacheDir, name).writeText("${handle.initResult.status}|$loaded|${identity.sessionId == identity.processId}")
        handle.close()
        Log.i("PerformanceAnrTest", "子进程平台判断完成")
        return Binder()
    }
}
