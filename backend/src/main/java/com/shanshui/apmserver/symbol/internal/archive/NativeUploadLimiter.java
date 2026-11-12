package com.shanshui.apmserver.symbol.internal.archive;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.LongSupplier;

/** 单实例 SO 接收与校验许可；取得后覆盖整个接收和清理生命周期。 */
public final class NativeUploadLimiter {
    /** 安全阶段日志不包含路径或上传材料。 */
    private static final Logger log = LoggerFactory.getLogger(NativeUploadLimiter.class);
    /** 一次压缩体与解压暂存预算总和。 */
    private static final long REQUIRED_DISK = 768L * 1024 * 1024;
    /** 处理期限；读取超时还需代理和 Servlet 配置。 */
    private static final long DEADLINE_NANOS = Duration.ofSeconds(60).toNanos();
    /** 不排队的单实例接收许可。 */
    private final Semaphore semaphore = new Semaphore(1);
    /** 从指定暂存卷读取可用空间。 */
    private final DiskSpace disk;
    /** 单调时钟，避免系统时间调整影响期限。 */
    private final LongSupplier clock;

    /** 使用真实磁盘余量及单调时钟。 */
    public NativeUploadLimiter() {
        this(path -> Files.getFileStore(path).getUsableSpace(), System::nanoTime);
    }

    /** 注入可控磁盘和时钟，用于验证失败清理。 */
    NativeUploadLimiter(DiskSpace disk, LongSupplier clock) {
        this.disk = disk;
        this.clock = clock;
    }

    /** 不等待取得许可，并在开始读取前检查目标卷空间。 */
    public Permit tryAcquire(Path stagingRoot) throws IOException {
        if (!semaphore.tryAcquire()) {
            log.warn("SO 接收被拒绝：并发许可已占用");
            throw new BusyException();
        }
        try {
            if (disk.usableBytes(stagingRoot) < REQUIRED_DISK) {
                throw new IOException("SO 暂存卷空间不足");
            }
            Permit permit = new Permit(clock.getAsLong() + DEADLINE_NANOS, semaphore);
            log.info("SO 接收许可已取得");
            return permit;
        } catch (IOException | RuntimeException error) {
            semaphore.release();
            log.warn("SO 接收许可检查失败，已释放许可");
            throw error;
        }
    }

    /** 安全检查可用空间的可注入边界。 */
    @FunctionalInterface
    interface DiskSpace {
        /** 返回指定文件卷当前可用字节数。 */
        long usableBytes(Path path) throws IOException;
    }

    /** 无空闲许可时立即返回，不建立等待队列。 */
    public static final class BusyException extends IOException {
        /** 创建可识别的并发拒绝错误。 */
        BusyException() {
            super("SO 接收繁忙");
        }
    }

    /** 单次接收的期限与自动释放门禁。 */
    public static final class Permit implements AutoCloseable {
        /** 后续循环使用同一单调截止时间。 */
        private final long deadlineNanos;
        /** 原许可的共享信号量。 */
        private final Semaphore semaphore;
        /** 防止多次关闭导致错误增加许可数。 */
        private final AtomicBoolean closed = new AtomicBoolean();

        /** 保存单次许可和截止时间。 */
        private Permit(long deadlineNanos, Semaphore semaphore) {
            this.deadlineNanos = deadlineNanos;
            this.semaphore = semaphore;
        }

        /** 返回使用 System.nanoTime 时间基准的截止值。 */
        public long deadlineNanos() {
            return deadlineNanos;
        }

        /** 清理完成后仅释放一次许可。 */
        @Override
        public void close() {
            if (closed.compareAndSet(false, true)) {
                semaphore.release();
                log.info("SO 接收许可已释放");
            }
        }
    }
}
