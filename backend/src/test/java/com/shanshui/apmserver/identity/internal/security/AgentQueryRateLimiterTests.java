package com.shanshui.apmserver.identity.internal.security;

import com.shanshui.apmserver.identity.api.AuthenticatedQueryToken;
import com.shanshui.apmserver.identity.internal.config.AgentQueryLimitProperties;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** 频率、并发、异常释放及无效凭据入口使用可控分钟时钟。 */
class AgentQueryRateLimiterTests {

    @Test
    void boundsTokenAppAndInvalidSourceWithoutLeakingPermits() {
        MutableClock clock = new MutableClock();
        AgentQueryLimitProperties config = new AgentQueryLimitProperties();
        config.setTokenPerMinute(2);
        config.setTokenConcurrent(1);
        config.setAppPerMinute(3);
        config.setAppConcurrent(2);
        config.setInvalidPerIpPerMinute(2);
        AgentQueryRateLimiter limiter = new AgentQueryRateLimiter(config, clock);
        UUID app = UUID.randomUUID();
        AuthenticatedQueryToken first = new AuthenticatedQueryToken(UUID.randomUUID(), app, "apm:read");
        AuthenticatedQueryToken second = new AuthenticatedQueryToken(UUID.randomUUID(), app, "apm:read");

        try (var permit = limiter.acquire(first)) {
            assertEquals(1, assertThrows(AgentRateLimitException.class,
                    () -> limiter.acquire(first)).retryAfterSeconds());
        }
        try (var ignored = limiter.acquire(first)) {
            // 上一许可已释放，第二次获取成功。
        }
        assertThrows(AgentRateLimitException.class, () -> limiter.acquire(first));
        try (var ignored = limiter.acquire(second)) {
            // 同应用的第三次请求允许，不借用第一个 Token 的独立额度。
        }
        assertThrows(AgentRateLimitException.class, () -> limiter.acquire(second));

        limiter.checkInvalidSource("192.0.2.1");
        limiter.recordInvalid("192.0.2.1");
        limiter.recordInvalid("192.0.2.1");
        assertThrows(AgentRateLimitException.class, () -> limiter.checkInvalidSource("192.0.2.1"));
        clock.advanceMinutes(1);
        limiter.checkInvalidSource("192.0.2.1");
        try (var ignored = limiter.acquire(first)) {
            // 分钟切换后额度恢复。
        }
    }

    private static final class MutableClock extends Clock {
        private final AtomicLong millis = new AtomicLong(1_780_000_000_000L);
        @Override public ZoneId getZone() { return ZoneId.of("UTC"); }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return Instant.ofEpochMilli(millis.get()); }
        @Override public long millis() { return millis.get(); }
        private void advanceMinutes(long minutes) { millis.addAndGet(minutes * 60_000); }
    }
}
