package com.shanshui.modularconsumer.crash

import android.app.Activity
import android.os.Bundle
import android.util.Log

/** 启动后执行 Crash 精简消费者烟测并立即退出。 */
class SmokeActivity : Activity() {
    /** 执行消费者初始化、关闭、重启恢复和 JSON 身份序列化验证。 */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        when (intent.getStringExtra("nativeProbe")) {
            "hold" -> { finish(); return }
            "disable-next-start" -> {
                // 当前监听保持运行，下一进程关闭 Native 以验证先恢复后上传。
                getSharedPreferences("native-probe", MODE_PRIVATE).edit().putBoolean("enabled", false).commit()
                Log.i(TAG, "native capture disabled for next start")
                finish()
                return
            }
            "signal" -> {
                Log.i(TAG, "triggering native signal")
                android.os.Process.sendSignal(android.os.Process.myPid(), 11)
                return
            }
        }
        runCatching { (application as CrashConsumerApplication).runSmokeCycle() }
            .onSuccess { Log.i(TAG, "consumer smoke passed payload=$it") }
            .onFailure { throwable ->
                Log.e(TAG, "consumer smoke failed", throwable)
                throw throwable
            }
        finish()
    }

    /** 当前烟测日志标签。 */
    private companion object {
        /** 当前消费者烟测日志标签。 */
        const val TAG = "CrashConsumerSmoke"
    }
}
