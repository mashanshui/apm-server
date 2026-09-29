package com.shanshui.apmserver.identity.internal.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** 查询 Token 单应用有效数量上限。 */
@ConfigurationProperties(prefix = "apm.query-token")
public class QueryTokenProperties {

    /** 默认最多保留二十个仍有效的凭据。 */
    private int maxActive = 20;

    /** 返回有效凭据上限。 */
    public int getMaxActive() { return maxActive; }

    /** 设置正数上限。 */
    public void setMaxActive(int maxActive) {
        if (maxActive < 1) throw new IllegalArgumentException("apm.query-token.max-active 必须大于零");
        this.maxActive = maxActive;
    }
}
