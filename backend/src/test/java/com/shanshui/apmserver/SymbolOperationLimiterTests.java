package com.shanshui.apmserver;

import com.shanshui.apmserver.symbol.api.SymbolParserBusyException;
import com.shanshui.apmserver.symbol.internal.application.SymbolOperationLimiter;
import com.shanshui.apmserver.symbol.internal.config.SymbolProperties;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** 验证上传校验和详情还原共享的有界并发许可。 */
class SymbolOperationLimiterTests {

    /** 达到许可上限时立即返回繁忙，释放后下一次请求能够继续。 */
    @Test
    void rejectsBusyOperationAndReleasesPermitExactlyOnce() {
        SymbolProperties properties = new SymbolProperties();
        properties.setMaxConcurrentOperations(1);
        SymbolOperationLimiter limiter = new SymbolOperationLimiter(properties);

        SymbolOperationLimiter.Permit first = limiter.acquire();
        assertThrows(SymbolParserBusyException.class, limiter::acquire);
        first.close();
        first.close();
        assertDoesNotThrow(() -> {
            try (SymbolOperationLimiter.Permit ignored = limiter.acquire()) {
                // 释放后应允许下一次操作进入。
            }
        });
    }
}
