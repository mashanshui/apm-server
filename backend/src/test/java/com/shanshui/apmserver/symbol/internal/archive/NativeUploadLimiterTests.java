package com.shanshui.apmserver.symbol.internal.archive;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** 验证并发许可、可用磁盘与失败释放。 */
class NativeUploadLimiterTests {
    /** 忙时立即拒绝，关闭幂等且期限使用单调时钟。 */
    @Test
    void permitIsExclusiveAndReleasedOnce() throws IOException {
        AtomicLong time = new AtomicLong(100);
        NativeUploadLimiter limiter = new NativeUploadLimiter(path -> Long.MAX_VALUE, time::get);
        var first = limiter.tryAcquire(Path.of("."));
        assertEquals(100 + TimeUnit.SECONDS.toNanos(60), first.deadlineNanos());
        assertThrows(NativeUploadLimiter.BusyException.class, () -> limiter.tryAcquire(Path.of(".")));
        first.close();
        first.close();
        try (var second = limiter.tryAcquire(Path.of("."))) {
            assertThrows(NativeUploadLimiter.BusyException.class, () -> limiter.tryAcquire(Path.of(".")));
        }
    }

    /** 磁盘不足或查询失败必须释放许可，使后续请求可重试。 */
    @Test
    void diskFailuresReleasePermit() throws IOException {
        AtomicLong available = new AtomicLong(0);
        NativeUploadLimiter limiter = new NativeUploadLimiter(path -> {
            if (available.get() < 0) throw new IOException("磁盘故障");
            return available.get();
        }, System::nanoTime);
        assertThrows(IOException.class, () -> limiter.tryAcquire(Path.of(".")));
        available.set(-1);
        assertThrows(IOException.class, () -> limiter.tryAcquire(Path.of(".")));
        available.set(Long.MAX_VALUE);
        try (var permit = limiter.tryAcquire(Path.of("."))) {
            assertThrows(NativeUploadLimiter.BusyException.class, () -> limiter.tryAcquire(Path.of(".")));
        }
    }
}
