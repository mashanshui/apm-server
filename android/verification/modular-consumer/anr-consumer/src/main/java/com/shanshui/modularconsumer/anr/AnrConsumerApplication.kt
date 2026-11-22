package com.shanshui.modularconsumer.anr

import android.app.Application
import com.shanshui.performance.anr.AnrComponent
import com.shanshui.performance.anr.AnrConfig
import com.shanshui.performance.anr.AnrHandle

/** 只依赖 ANR 坐标的无网络配置消费者。 */
class AnrConsumerApplication : Application() {
    /** 保留实际 ANR 状态及可独立读取的报告入口。 */
    lateinit var anr: AnrHandle
        private set

    /** 在主线程显式启动 Matrix ANR，不提供 App Key 或服务地址。 */
    override fun onCreate() {
        super.onCreate()
        anr = AnrComponent.start(this, AnrConfig(enabled = true))
    }
}
