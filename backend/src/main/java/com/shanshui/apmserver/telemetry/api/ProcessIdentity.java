package com.shanshui.apmserver.telemetry.api;

import java.util.regex.Pattern;

/** 提供进程实例 UUID v4 的统一格式判断，避免各接收入口出现不同校验口径。 */
public final class ProcessIdentity {

    /** 标准连字符 UUID v4，允许十六进制字母使用大写或小写。 */
    private static final Pattern UUID_V4 = Pattern.compile(
            "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-4[0-9a-fA-F]{3}-[89abAB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}$");

    /** 阻止实例化纯工具类。 */
    private ProcessIdentity() {
    }

    /** 判断给定值是否为标准连字符 UUID v4。 */
    public static boolean isUuidV4(String value) {
        return value != null && UUID_V4.matcher(value).matches();
    }
}
