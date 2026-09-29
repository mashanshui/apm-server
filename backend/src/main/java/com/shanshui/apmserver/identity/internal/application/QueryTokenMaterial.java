package com.shanshui.apmserver.identity.internal.application;

import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.regex.Pattern;

/** 高熵应用查询凭据生成与不可逆摘要。 */
@Component
public class QueryTokenMaterial {

    /** 格式白名单用于快速拒绝错误凭据，完整值从不进入日志。 */
    private static final Pattern FORMAT = Pattern.compile("^apm_qt_[A-Za-z0-9_-]{43}$");
    private final SecureRandom random;

    /** 生产环境使用系统安全随机源。 */
    @Autowired
    public QueryTokenMaterial() { this(new SecureRandom()); }

    /** 可注入随机源以验证边界，测试源只在测试中使用。 */
    QueryTokenMaterial(SecureRandom random) { this.random = random; }

    /** 生成 256 bit 随机秘密，以 URL 安全无填充格式编码。 */
    public String generate() {
        byte[] secret = new byte[32];
        random.nextBytes(secret);
        return "apm_qt_" + Base64.getUrlEncoder().withoutPadding().encodeToString(secret);
    }

    /** 校验查询凭据是否符合固定格式。 */
    public boolean isWellFormed(String token) { return token != null && FORMAT.matcher(token).matches(); }

    /** 仅显示足以区分列表项的短前缀。 */
    public String displayPrefix(String token) { return token.substring(0, 15); }

    /** 计算高熵秘密的 SHA-256 摘要。 */
    public byte[] digest(String token) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("JVM 不支持 SHA-256", ex);
        }
    }
}
