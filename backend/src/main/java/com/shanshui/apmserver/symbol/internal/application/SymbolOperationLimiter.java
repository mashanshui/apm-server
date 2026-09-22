package com.shanshui.apmserver.symbol.internal.application;

import com.shanshui.apmserver.symbol.api.SymbolParserBusyException;
import com.shanshui.apmserver.symbol.internal.config.SymbolProperties;
import org.springframework.stereotype.Component;

import java.util.concurrent.Semaphore;

/** 统一限制 mapping 上传校验和详情 Retrace 的并发资源。 */
@Component
public class SymbolOperationLimiter {

    /** 共享的公平信号量。 */
    private final Semaphore permits;

    /** 根据配置初始化符号解析许可。 */
    public SymbolOperationLimiter(SymbolProperties properties) {
        this.permits = new Semaphore(properties.getMaxConcurrentOperations(), true);
    }

    /** 立即取得一个解析许可，资源满载时返回可重试错误。 */
    public Permit acquire() {
        if (!permits.tryAcquire()) {
            throw new SymbolParserBusyException();
        }
        return new Permit();
    }

    /** 一次受控的 mapping 解析许可。 */
    public final class Permit implements AutoCloseable {

        /** 防止重复释放。 */
        private boolean closed;

        /** 释放一个共享解析许可。 */
        @Override
        public synchronized void close() {
            if (!closed) {
                closed = true;
                permits.release();
            }
        }
    }
}
