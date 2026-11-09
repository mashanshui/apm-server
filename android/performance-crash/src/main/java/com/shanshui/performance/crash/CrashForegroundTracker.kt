package com.shanshui.performance.crash

import android.app.Activity
import android.app.Application
import android.os.Bundle
import java.io.Closeable

/** 缓存已观察到的 Activity 前后台状态，尚未观察时保留未知。 */
internal class CrashForegroundState {
    /** 已观察到的前台 Activity 数量。 */
    private var startedCount = 0
    /** 是否观察到至少一次启动。 */
    private var observedStart = false

    /** 记录 Activity 启动。 */
    @Synchronized
    fun started() { observedStart = true; startedCount++ }

    /** 记录已观察 Activity 停止，不允许计数为负。 */
    @Synchronized
    fun stopped() { if (startedCount > 0) startedCount-- }

    /** 未观察到启动时返回 null，而不是猜测为后台。 */
    @Synchronized
    fun current(): Boolean? = if (observedStart) startedCount > 0 else null
}

/** 使用公开生命周期回调维护 Crash 所需状态，不依赖 Metrics。 */
internal class CrashForegroundTracker(
    /** 接收生命周期回调的应用。 */ private val application: Application,
    /** 线程安全的状态快照。 */ private val state: CrashForegroundState = CrashForegroundState(),
) : Application.ActivityLifecycleCallbacks, Closeable {
    init { application.registerActivityLifecycleCallbacks(this) }

    /** 取得当前已观察状态。 */
    fun current(): Boolean? = state.current()
    /** 记录 Activity 启动。 */
    override fun onActivityStarted(activity: Activity) = state.started()
    /** 记录 Activity 停止。 */
    override fun onActivityStopped(activity: Activity) = state.stopped()
    /** 此采集器不依赖创建回调。 */
    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
    /** 此采集器不依赖恢复回调。 */
    override fun onActivityResumed(activity: Activity) = Unit
    /** 此采集器不依赖暂停回调。 */
    override fun onActivityPaused(activity: Activity) = Unit
    /** 此采集器不依赖状态保存回调。 */
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
    /** 此采集器不依赖销毁回调。 */
    override fun onActivityDestroyed(activity: Activity) = Unit
    /** 停止观察时解除回调。 */
    override fun close() { application.unregisterActivityLifecycleCallbacks(this) }
}
