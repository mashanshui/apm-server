package com.shanshui.apmserver.identity.internal.security;

import com.shanshui.apmserver.identity.api.AuthenticatedQueryToken;
import com.shanshui.apmserver.identity.internal.config.AgentQueryLimitProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/** 单实例 Token/应用频率与并发限制，拒绝直接 HTTP 绕过 MCP。 */
@Component
public class AgentQueryRateLimiter {

    private final AgentQueryLimitProperties limits;
    private final Clock clock;
    private final Map<String, Window> windows = new HashMap<>();

    @Autowired
    public AgentQueryRateLimiter(AgentQueryLimitProperties limits) {
        this(limits, Clock.systemUTC());
    }

    /** 可控时钟用于分钟边界和异常释放测试。 */
    public AgentQueryRateLimiter(AgentQueryLimitProperties limits, Clock clock) {
        this.limits = limits;
        this.clock = clock;
    }

    /** 在数据库认证前限制重复无效凭据来源。 */
    public synchronized void checkInvalidSource(String ip) {
        Window window = current("invalid:" + ip);
        if (window.count >= limits.getInvalidPerIpPerMinute()) throw limited();
    }

    /** 仅记录确实无效的 Bearer；数据库临时故障不计作猜测。 */
    public synchronized void recordInvalid(String ip) {
        current("invalid:" + ip).count++;
    }

    /** 原子检查两个频率和并发上限，返回必须关闭的许可。 */
    public synchronized Permit acquire(AuthenticatedQueryToken token) {
        String tokenKey = "token:" + token.tokenId();
        String appKey = "app:" + token.appId();
        Window tokenWindow = current(tokenKey);
        Window appWindow = current(appKey);
        if (tokenWindow.count >= limits.getTokenPerMinute() || appWindow.count >= limits.getAppPerMinute()) {
            throw limited();
        }
        if (tokenWindow.active >= limits.getTokenConcurrent() || appWindow.active >= limits.getAppConcurrent()) {
            throw new AgentRateLimitException(1);
        }
        tokenWindow.count++;
        appWindow.count++;
        tokenWindow.active++;
        appWindow.active++;
        return new Permit(tokenWindow, appWindow);
    }

    /** 每分钟切换窗口，空闲旧键及时清理并限制陌生 IP 占用。 */
    private Window current(String key) {
        long minute = clock.millis() / 60_000;
        Window old = windows.get(key);
        if (old != null && old.minute == minute) return old;
        if (windows.size() >= limits.getMaxTrackedKeys()) {
            Iterator<Map.Entry<String, Window>> iterator = windows.entrySet().iterator();
            while (iterator.hasNext()) {
                Window candidate = iterator.next().getValue();
                if (candidate.minute != minute && candidate.active == 0) iterator.remove();
            }
            if (windows.size() >= limits.getMaxTrackedKeys()) throw limited();
        }
        Window fresh = new Window(minute);
        windows.put(key, fresh);
        return fresh;
    }

    private AgentRateLimitException limited() {
        return new AgentRateLimitException((int) (60 - (clock.millis() / 1000) % 60));
    }

    private static final class Window {
        private final long minute;
        private int count;
        private int active;
        private Window(long minute) { this.minute = minute; }
    }

    /** 许可证关闭可重入，异常路径也只释放一次。 */
    public final class Permit implements AutoCloseable {
        private final Window token;
        private final Window app;
        private boolean closed;

        private Permit(Window token, Window app) {
            this.token = token;
            this.app = app;
        }

        @Override
        public void close() {
            synchronized (AgentQueryRateLimiter.this) {
                if (!closed) {
                    token.active--;
                    app.active--;
                    closed = true;
                }
            }
        }
    }
}
