package com.shanshui.performance.anr.internal;

/** 保留 Matrix 的消息超时阈值和系统错误状态轮询规则。 */
final class MatrixAnrDecision {
    /** 前台消息超时阈值。 */
    private static final long FOREGROUND_THRESHOLD = -2000L;
    /** 后台消息超时阈值。 */
    private static final long BACKGROUND_THRESHOLD = -10000L;
    /** 原有错误状态检查次数。 */
    private static final int POLL_COUNT = 40;
    /** 原有错误状态检查间隔。 */
    private static final long POLL_INTERVAL_MILLIS = 500L;

    /** 纯工具类不创建实例。 */
    private MatrixAnrDecision() { }

    /** 使用严格小于比较，保持 Matrix 原有前后台边界。 */
    static boolean messageBlocked(long delayMillis, boolean foreground) {
        return delayMillis < (foreground ? FOREGROUND_THRESHOLD : BACKGROUND_THRESHOLD);
    }

    /** 按原次数与间隔读取错误状态，命中后立即返回。 */
    static <T> T poll(Reader<T> reader, Sleeper sleeper) {
        for (int count = 0; count < POLL_COUNT; count++) {
            try {
                T result = reader.read();
                if (result != null) return result;
                sleeper.sleep(POLL_INTERVAL_MILLIS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                MatrixLog.e("MatrixAnrDecision", "错误状态轮询已中断");
                return null;
            } catch (Throwable failure) {
                MatrixLog.e("MatrixAnrDecision", "错误状态轮询失败");
                return null;
            }
        }
        return null;
    }

    /** 单次错误状态读取。 */
    interface Reader<T> {
        /** 命中本进程时返回记录，否则返回空。 */
        T read();
    }

    /** 可替换的原轮询等待。 */
    interface Sleeper {
        /** 等待指定毫秒数。 */
        void sleep(long millis) throws InterruptedException;
    }
}
