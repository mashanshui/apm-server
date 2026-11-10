package com.shanshui.performance.crash

import android.app.Application
import android.os.SystemClock
import android.system.Os
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.gson.Gson
import com.shanshui.performance.core.PerformanceEventContextFactory
import com.shanshui.performance.network.CrashEvent
import com.shanshui.performance.network.NetworkConfig
import com.shanshui.performance.network.TransportSession
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

/** 验证同一进程的后台调度可以恢复瞬时读取失败的 Native 暂存。 */
@RunWith(AndroidJUnit4::class)
class NativeRecoveryRetryTest {
    /** 注入文件权限故障，再恢复权限，验证无需重启即可冻结原身份事件。 */
    @Test
    fun retriesUnreadableIdentityDuringPeriodicFlush() {
        /** 设备测试 APK 自己的应用。 */
        val application = ApplicationProvider.getApplicationContext<Application>()
        /** 此次测试使用的合成事件标识。 */
        val eventId = "native-retry-${UUID.randomUUID()}"
        /** 不属于任何真实进程的合成原 PID 和 TID。 */
        val originalPid = 2_147_483_000
        /** 测试应用自己的 Crash 队列目录。 */
        val root = File(application.noBackupFilesDir, CrashReporter.QUEUE_DIRECTORY)
        /** 存放合成原进程材料的暂存目录。 */
        val stagingRoot = File(root, "native-staging")
        /** 负责创建原始暂存的管理器。 */
        val stage = NativeCaptureStaging(stagingRoot)
        /** 只包含合成字段的原始事件身份。 */
        val event = CrashEvent(
            schemaVersion = 2, eventId = eventId, eventType = "crash", occurredAt = System.currentTimeMillis(),
            sessionId = "synthetic-session", processId = UUID.randomUUID().toString(), anonymousDeviceId = "synthetic-device",
            packageName = application.packageName, appVersion = "1", versionCode = 1, buildId = "synthetic-build",
            environment = "test", channel = "test", osVersion = "test", deviceModel = "synthetic",
        )
        /** 用于注入读取故障的暂存文件。 */
        val prepared = requireNotNull(stage.prepare(event, originalPid))
        /** 恢复成功时生成的最终事件文件。 */
        val frozen = File(root, "events/$eventId.json")
        /** 只连接本机不可用端口，确保测试不向外发送。 */
        val transport = TransportSession.create(NetworkConfig("http://127.0.0.1:1/", "synthetic-key"))
        /** 通过公开装配入口启动的后台采集句柄。 */
        var reporter: CrashHandle? = null
        try {
            prepared.coreFile.writeBytes(core(originalPid))
            // 用权限故障模拟读取失败，不能把有效身份错误隔离。
            Os.chmod(prepared.identityFile.absolutePath, 0)
            try {
                prepared.identityFile.readText()
                fail("测试进程必须无法读取被限制的身份文件")
            } catch (_: IOException) {
                // 预期的瞬时 I/O 故障。
            }
            reporter = CrashComponent.start(
                application, CrashConfig(nativeEnabled = false, uploadIntervalMillis = 500),
                PerformanceEventContextFactory.create(application), transport,
            )
            assertTrue(prepared.identityFile.isFile)
            assertTrue(prepared.coreFile.isFile)
            assertFalse(File(stagingRoot, "$eventId.invalid").exists())
            assertFalse(frozen.exists())
            Os.chmod(prepared.identityFile.absolutePath, 384)
            /** 等待真实的周期刷新，而不是直接调用暂存恢复方法。 */
            val deadline = SystemClock.elapsedRealtime() + 10_000
            while (!frozen.exists() && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(100)
            assertTrue("恢复权限后周期刷新应导入 Native 事件", frozen.isFile)
            /** 即使本机上传失败，也必须保留原事件与原进程身份。 */
            val restored = Gson().fromJson(frozen.readText(), CrashQueueRecord::class.java).event
            assertEquals(eventId, restored.eventId)
            assertEquals(event.processId, restored.processId)
            assertEquals(originalPid, restored.crash?.nativeCrash?.systemTid)
        } finally {
            reporter?.close()
            transport.close()
            if (prepared.identityFile.exists()) Os.chmod(prepared.identityFile.absolutePath, 384)
            // 只移除本测试生成的合成事件，不清理设备上的其他队列内容。
            stagingRoot.listFiles().orEmpty().filter { it.name.startsWith("$eventId.") }.forEach { it.delete() }
            frozen.delete()
        }
    }

    /** 构造含校验值的最小有效核心，不包含真实设备现场。 */
    private fun core(pid: Int): ByteArray {
        /** 与 Native 核心格式一致的字节序和固定长度。 */
        val buffer = ByteBuffer.allocate(60).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put(byteArrayOf(65, 78, 67, 82)).putInt(1).putInt(buffer.capacity()).putLong(System.currentTimeMillis())
        buffer.putInt(pid).putInt(pid).putInt(11).putInt(1).putInt(1).putLong(0).putInt(0).putInt(1)
        /** FNV-1a 校验只覆盖核心正文。 */
        var checksum = -2128831035
        for (index in 0 until buffer.position()) checksum = (checksum xor (buffer.array()[index].toInt() and 0xff)) * 16777619
        buffer.putInt(checksum)
        return buffer.array()
    }
}
