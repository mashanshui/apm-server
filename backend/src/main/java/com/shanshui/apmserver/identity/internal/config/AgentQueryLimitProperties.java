package com.shanshui.apmserver.identity.internal.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Agent 入口单实例内存额度；部署多个实例前须更换为共享配额。 */
@ConfigurationProperties(prefix = "apm.agent.limits")
public class AgentQueryLimitProperties {
    private int tokenPerMinute = 60;
    private int tokenConcurrent = 2;
    private int appPerMinute = 300;
    private int appConcurrent = 8;
    private int invalidPerIpPerMinute = 30;
    private int maxTrackedKeys = 10_000;

    public int getTokenPerMinute() { return tokenPerMinute; }
    public void setTokenPerMinute(int value) { tokenPerMinute = value; }
    public int getTokenConcurrent() { return tokenConcurrent; }
    public void setTokenConcurrent(int value) { tokenConcurrent = value; }
    public int getAppPerMinute() { return appPerMinute; }
    public void setAppPerMinute(int value) { appPerMinute = value; }
    public int getAppConcurrent() { return appConcurrent; }
    public void setAppConcurrent(int value) { appConcurrent = value; }
    public int getInvalidPerIpPerMinute() { return invalidPerIpPerMinute; }
    public void setInvalidPerIpPerMinute(int value) { invalidPerIpPerMinute = value; }
    public int getMaxTrackedKeys() { return maxTrackedKeys; }
    public void setMaxTrackedKeys(int value) { maxTrackedKeys = value; }
}
