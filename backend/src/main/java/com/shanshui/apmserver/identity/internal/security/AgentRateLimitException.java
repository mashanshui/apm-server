package com.shanshui.apmserver.identity.internal.security;

/** Agent 入口本地配额或并发已满。 */
public class AgentRateLimitException extends RuntimeException {
    private final int retryAfterSeconds;

    public AgentRateLimitException(int retryAfterSeconds) {
        super("Agent 查询额度已满，请稍后重试");
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public int retryAfterSeconds() { return retryAfterSeconds; }
}
