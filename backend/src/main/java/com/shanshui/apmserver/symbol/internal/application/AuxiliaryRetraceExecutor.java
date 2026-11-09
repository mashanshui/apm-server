package com.shanshui.apmserver.symbol.internal.application;

import com.shanshui.apmserver.symbol.api.SymbolFileLease;
import com.shanshui.apmserver.symbol.api.SymbolParserBusyException;
import com.shanshui.apmserver.symbol.api.SymbolicationResult;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** 单工作线程、无排队的辅助 Retrace；超时不假设 R8 已停止。 */
final class AuxiliaryRetraceExecutor implements AutoCloseable {
    /** 可注入的有界还原运算。 */
    @FunctionalInterface
    interface Operation {
        /** 使用固定文件与输出上限执行完整还原。 */
        SymbolicationResult retrace(Path mapping, List<String> lines, long maxOutputBytes);
    }

    /** 全进程工作交接和唯一在途任务的同步门禁。 */
    private static final Object LOCK = new Object();
    /** 进程中唯一的在途任务，不因 Spring 上下文重建而增加工作者。 */
    private static Work active;
    /** 进程中唯一的守护线程。 */
    private static final Thread WORKER = startWorker();
    /** 与上传校验和主异常还原共享的操作许可。 */
    private final SymbolOperationLimiter limiter;
    /** 官方 R8 还原调用。 */
    private final Operation operation;
    /** 当前注册表实例是否停止接收新任务，由进程门禁保护。 */
    private boolean closed;

    /** 绑定共享许可和具体还原器，工作线程在整个进程中复用。 */
    AuxiliaryRetraceExecutor(SymbolOperationLimiter limiter, Operation operation) {
        this.limiter = limiter;
        this.operation = operation;
    }

    /** 忙时立即失败；超时只关闭结果接收并尝试中断当前工作。 */
    SymbolicationResult retrace(SymbolFileLease mapping, List<String> lines, long maxOutputBytes, Duration wait) {
        if (mapping == null || lines == null || maxOutputBytes < 1 || wait == null || wait.isZero() || wait.isNegative()) {
            return SymbolicationResult.failed("invalid_input");
        }
        Work work;
        synchronized (LOCK) {
            if (closed || active != null) return SymbolicationResult.busy();
            try {
                // 在原租约释放前取得相同版本的独立引用。
                work = new Work(this, mapping.retain(), List.copyOf(lines), maxOutputBytes);
            } catch (RuntimeException unavailable) {
                return SymbolicationResult.failed("mapping_unavailable");
            }
            active = work;
            LOCK.notifyAll();
        }
        try {
            if (work.finished.await(wait.toNanos(), TimeUnit.NANOSECONDS)) return work.result;
            abandon(work);
            return SymbolicationResult.failed("timeout");
        } catch (InterruptedException interrupted) {
            abandon(work);
            Thread.currentThread().interrupt();
            return SymbolicationResult.failed("interrupted");
        }
    }

    /** 只有仍在执行的同一工作可被中断，避免影响后续任务。 */
    private void abandon(Work work) {
        synchronized (LOCK) {
            if (active == work) {
                work.abandoned = true;
                WORKER.interrupt();
            }
        }
    }

    /** 创建全进程唯一工作线程，使用守护线程避免阻止 JVM 退出。 */
    private static Thread startWorker() {
        Thread thread = new Thread(AuxiliaryRetraceExecutor::run, "crash-auxiliary-retrace");
        thread.setDaemon(true);
        thread.start();
        return thread;
    }

    /** 执行唯一任务，实际结束后才释放共享许可与固定文件租约。 */
    private static void run() {
        while (true) {
            Work work;
            synchronized (LOCK) {
                while (active == null) {
                    try { LOCK.wait(); }
                    catch (InterruptedException ignored) { /* 重新等待下一次交接。 */ }
                }
                work = active;
            }
            SymbolicationResult result = SymbolicationResult.failed("invalid_stack");
            try (SymbolFileLease pinned = work.mapping) {
                if (work.abandoned) {
                    result = SymbolicationResult.failed("timeout");
                } else try (SymbolOperationLimiter.Permit ignored = work.owner.limiter.acquire()) {
                    if (work.abandoned) result = SymbolicationResult.failed("timeout");
                    else result = bounded(work.owner.operation.retrace(pinned.path(), work.lines, work.maxOutputBytes), work.maxOutputBytes);
                } catch (SymbolParserBusyException busy) {
                    result = SymbolicationResult.busy();
                }
            } catch (Throwable failure) {
                // 错误内容与文件路径不写日志；结束后释放工作者与文件引用。
                result = SymbolicationResult.failed("mapping_unavailable");
            } finally {
                synchronized (LOCK) {
                    work.result = result;
                    if (active == work) active = null;
                    work.finished.countDown();
                    LOCK.notifyAll();
                }
                // 不让取消标志泄漏给下一次 R8 调用或空闲等待。
                Thread.interrupted();
            }
        }
    }

    /** 只承认有界、非空的完整成功输出。 */
    private static SymbolicationResult bounded(SymbolicationResult value, long maxBytes) {
        if (value == null) return SymbolicationResult.failed("invalid_stack");
        if (value.status() != SymbolicationResult.Status.SYMBOLICATED) return value;
        if (value.text() == null || value.text().isBlank()) return SymbolicationResult.failed("empty_output");
        if (value.text().getBytes(StandardCharsets.UTF_8).length > maxBytes) return SymbolicationResult.failed("output_limit");
        return value;
    }

    /** 停止接收并尝试中断；不提前释放还在运行的任务资源。 */
    @Override
    public void close() {
        synchronized (LOCK) {
            closed = true;
            if (active != null && active.owner == this) {
                active.abandoned = true;
                WORKER.interrupt();
            }
        }
    }

    /** 一次交接的数据和仅对调用方可见的有界结果。 */
    private static final class Work {
        /** 此次任务使用的注册表实例与共享许可。 */ final AuxiliaryRetraceExecutor owner;
        /** 与主异常相同文件版本的独立租约。 */ final SymbolFileLease mapping;
        /** 有界且不可变的线程栈行。 */ final List<String> lines;
        /** 单次输出字节上限。 */ final long maxOutputBytes;
        /** 实际工作结束的信号。 */ final CountDownLatch finished = new CountDownLatch(1);
        /** 调用方不再接收结果。 */ volatile boolean abandoned;
        /** 任务结果，先写后释放 finished。 */ volatile SymbolicationResult result;

        /** 固定本次任务的全部输入。 */
        Work(AuxiliaryRetraceExecutor owner, SymbolFileLease mapping, List<String> lines, long maxOutputBytes) {
            this.owner = owner;
            this.mapping = mapping;
            this.lines = lines;
            this.maxOutputBytes = maxOutputBytes;
        }
    }
}
