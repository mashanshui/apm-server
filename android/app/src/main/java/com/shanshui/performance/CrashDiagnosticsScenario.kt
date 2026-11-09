package com.shanshui.performance

import java.io.Closeable
import java.io.File
import java.io.FileInputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** 在示例应用中保持合成线程和 FD，供真实崩溃时采集。 */
internal class CrashDiagnosticsScenario private constructor(
    /** 存放合成内容的临时文件。 */ private val file: File,
    /** 保持打开的合成文件描述符。 */ private val input: FileInputStream,
    /** 释放持锁线程的信号。 */ private val release: CountDownLatch,
    /** 持有同一把锁的线程。 */ private val holder: Thread,
    /** 尝试进入同一把锁的同名线程。 */ private val blocked: Thread,
) : Closeable {
    /** 退出页面时释放测试线程和文件，不保留用户数据。 */
    override fun close() {
        release.countDown()
        runCatching { input.close() }
        runCatching { holder.join(1000) }
        runCatching { blocked.join(1000) }
        file.delete()
    }

    companion object {
        /** 初始化同名持锁/阻塞线程和一个合成 FD。 */
        fun prepare(directory: File): CrashDiagnosticsScenario {
            /** 固定合成文件不含真实业务数据。 */
            val file = File.createTempFile("crash-diagnostic-", ".txt", directory)
            file.writeText("synthetic crash diagnostics\n")
            /** 保持文件打开直到页面退出或进程崩溃。 */
            val input = FileInputStream(file)
            /** 通过门闩使两条线程进入确定的竞争关系。 */
            val lock = Any()
            val holding = CountDownLatch(1)
            val attempting = CountDownLatch(1)
            val release = CountDownLatch(1)
            val holder = Thread({
                synchronized(lock) {
                    holding.countDown()
                    release.await()
                }
            }, "crash-same-name").apply { isDaemon = true }
            val blocked = Thread({
                attempting.countDown()
                synchronized(lock) { /* 持锁线程释放后退出。 */ }
            }, "crash-same-name").apply { isDaemon = true }
            val scenario = CrashDiagnosticsScenario(file, input, release, holder, blocked)
            try {
                holder.start()
                check(holding.await(1, TimeUnit.SECONDS)) { "Unable to prepare holder" }
                blocked.start()
                check(attempting.await(1, TimeUnit.SECONDS)) { "Unable to prepare blocked thread" }
                return scenario
            } catch (failure: Throwable) {
                scenario.close()
                throw failure
            }
        }
    }
}
