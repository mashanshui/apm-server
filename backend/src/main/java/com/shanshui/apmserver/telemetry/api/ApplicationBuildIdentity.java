package com.shanshui.apmserver.telemetry.api;

import java.util.List;

/** 符号匹配使用的必填事件身份；mapping 登记的默认范围由管理入口单独处理。 */
public final class ApplicationBuildIdentity {

    /** 工具类不创建实例。 */
    private ApplicationBuildIdentity() {
    }

    /** 校验版本、构建必填及原文的长度、边缘空白和控制字符。 */
    public static void validate(String version, String buildId, List<ValidationIssue> issues) {
        validateValue(version, "appVersion", 128, issues);
        validateValue(buildId, "buildId", 256, issues);
    }

    /** 检查单个必填标识，不将非法值静默转换为空值。 */
    private static void validateValue(String value, String field, int limit,
                                      List<ValidationIssue> issues) {
        if (value == null || value.isEmpty() || value.codePoints().allMatch(ApplicationBuildIdentity::isSpace)) {
            issues.add(new ValidationIssue("MISSING_" + field.toUpperCase(java.util.Locale.ROOT), field + " 不能为空"));
            return;
        }
        if (value.codePoints().anyMatch(Character::isISOControl)) {
            issues.add(new ValidationIssue("INVALID_IDENTITY", field + " 不能包含控制字符"));
        } else if (value.length() > limit) {
            issues.add(new ValidationIssue("FIELD_TOO_LONG", field + " 超过长度上限"));
        } else if (isSpace(value.codePointAt(0)) || isSpace(value.codePointBefore(value.length()))) {
            issues.add(new ValidationIssue("INVALID_IDENTITY", field + " 首尾不能包含空白"));
        }
    }
    /** 识别 Unicode 空格和 BOM，避免不可见字符冒充构建身份。 */
    private static boolean isSpace(int codePoint) {
        return Character.isWhitespace(codePoint) || Character.isSpaceChar(codePoint) || codePoint == 0xfeff;
    }
}
