package com.shanshui.modularconsumer.crash

import android.app.Application
import com.shanshui.performance.core.PerformanceEventContextFactory
import com.shanshui.performance.core.SharedMetadataConfig
import com.shanshui.performance.crash.CrashComponent
import com.shanshui.performance.crash.CrashConfig
import com.shanshui.performance.network.NetworkConfig
import com.shanshui.performance.network.TransportSession
import java.io.File
import org.json.JSONObject

/** 仅 Crash 的独立消费者，验证发布坐标可完成公开组件装配。 */
class CrashConsumerApplication : Application() {
    /** 保存本次独立消费者创建的 Crash 句柄。 */
    private var crashHandle: AutoCloseable? = null

    /** 保存本次独立消费者创建的传输会话。 */
    private var session: TransportSession? = null

    /** 保存本次独立消费者创建的共享事件上下文。 */
    private var eventContext: com.shanshui.performance.core.PerformanceEventContext? = null

    /** 使用发布坐标创建共享上下文、Transport 和 Crash 组件。 */
    override fun onCreate() {
        super.onCreate()
        initializeComponents()
    }

    /** 运行 Release 设备烟测，覆盖身份、序列化、队列目录持久化以及重复关闭。 */
    fun runSmokeCycle(): String {
        val firstContext = requireNotNull(eventContext) { "Crash consumer was not initialized" }
        val firstSessionId = firstContext.runtimeIdentity.sessionId
        val firstProcessId = firstContext.runtimeIdentity.processId
        val queueRoot = File(noBackupFilesDir, "performance-crash-reporter")
        check(queueRoot.isDirectory) { "Crash queue directory was not created" }
        val serialized = JSONObject()
            .put("sessionId", firstSessionId)
            .put("processId", firstProcessId)
            .toString()
        val restored = JSONObject(serialized)
        check(restored.getString("sessionId") == firstSessionId)
        closeComponents()
        initializeComponents()
        val secondContext = requireNotNull(eventContext)
        check(secondContext.runtimeIdentity.sessionId == firstSessionId)
        check(secondContext.runtimeIdentity.processId == firstProcessId)
        closeComponents()
        check(queueRoot.isDirectory) { "Crash queue directory was not recoverable" }
        return serialized
    }

    /** 创建共享上下文、Transport 和 Crash 组件。 */
    private fun initializeComponents() {
        val context = PerformanceEventContextFactory.create(this, SharedMetadataConfig())
        // 烟测默认只连接模拟器回环地址，避免向外部服务发送合成事件。
        val transport = TransportSession.create(NetworkConfig("http://127.0.0.1:1/", "consumer-key"))
        eventContext = context
        session = transport
        // 下次启动可单独关闭 Native，新进程仍应恢复此前已提交的暂存。
        val nativeEnabled = getSharedPreferences("native-probe", MODE_PRIVATE).getBoolean("enabled", true)
        crashHandle = CrashComponent.start(this, CrashConfig(nativeEnabled = nativeEnabled), context, transport, 2)
        android.util.Log.i("CrashConsumerSmoke", "native capture configured: enabled=$nativeEnabled")
    }

    /** 按组件所有权逆序关闭 Crash 和 Transport。 */
    private fun closeComponents() {
        crashHandle?.close()
        crashHandle = null
        session?.close()
        session = null
        eventContext = null
    }
}
