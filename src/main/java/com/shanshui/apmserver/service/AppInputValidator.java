package com.shanshui.apmserver.service;

import java.util.Locale;
import java.util.regex.Pattern;

public final class AppInputValidator {

    private static final Pattern APP_PACKAGE_NAME = Pattern.compile(
            "^[a-z][a-z0-9_]*(\\.[a-z][a-z0-9_]*)+$");

    private AppInputValidator() {
    }

    public static String normalizeName(String value) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.isEmpty() || normalized.length() > 100) {
            throw new InvalidAppInputException("name", "APP_NAME_INVALID", "应用名称不能为空且不能超过 100 个字符");
        }
        return normalized;
    }

    public static String normalizePackageName(String value) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.length() > 255 || !APP_PACKAGE_NAME.matcher(normalized).matches()) {
            throw new InvalidAppInputException("packageName", "PACKAGE_NAME_INVALID",
                    "应用包名必须是 255 个字符以内、至少包含两个以小写字母开头的点分段，且只包含小写字母、数字或下划线");
        }
        return normalized;
    }

    public static String normalizeDescription(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim();
        if (normalized.length() > 500) {
            throw new InvalidAppInputException("description", "APP_DESCRIPTION_INVALID", "应用描述不能超过 500 个字符");
        }
        return normalized.isEmpty() ? null : normalized;
    }

    public static String normalizeEmail(String value) {
        String normalized = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        if (normalized.isEmpty() || normalized.length() > 320 || !normalized.contains("@")) {
            throw new InvalidAppInputException("email", "EMAIL_INVALID", "请输入有效的邮箱地址");
        }
        return normalized;
    }
}
