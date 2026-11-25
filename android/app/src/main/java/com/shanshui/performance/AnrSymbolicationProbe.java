package com.shanshui.performance;

import android.os.SystemClock;
import android.util.Log;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** 供隔离联调验证混淆堆栈和系统 trace 锁记录的受控阻塞入口。 */
public final class AnrSymbolicationProbe {
    /** 避免记录任何诊断正文的日志标签。 */
    private static final String TAG = "AnrSymbolProbe";
    /** 工作线程持有、主线程等待的测试锁。 */
    private static final Object LOCK = new Object();

    /** 测试入口无需创建实例。 */
    private AnrSymbolicationProbe() { }

    /** 让具名工作线程短暂持锁，以触发真实输入超时并保留可辨认的 Java 帧。 */
    public static void blockMainThread() {
        /** 主线程确认工作线程已持锁后再进入阻塞。 */
        final CountDownLatch acquired = new CountDownLatch(1);
        /** 线程名称用于核对系统 trace 中同一把锁的持有者。 */
        final Thread owner = new Thread(() -> {
            synchronized (LOCK) {
                acquired.countDown();
                Log.i(TAG, "ANR 测试工作线程已持锁");
                SystemClock.sleep(15_000);
                Log.i(TAG, "ANR 测试工作线程即将释放锁");
            }
        }, "anr-test-lock-owner");
        owner.start();
        try {
            if (!acquired.await(2, TimeUnit.SECONDS)) {
                Log.w(TAG, "ANR 测试工作线程未及时持锁");
                return;
            }
            synchronized (LOCK) {
                Log.i(TAG, "ANR 测试主线程已取得锁");
            }
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            Log.w(TAG, "ANR 测试等待被中断");
        }
    }
}
