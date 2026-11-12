package com.shanshui.apmserver;

import com.shanshui.apmserver.telemetry.api.ApplicationBuildIdentity;
import com.shanshui.apmserver.telemetry.api.ValidationIssue;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 符号匹配接收非空事件构建，默认 mapping 不允许伪造缺失事件身份。 */
class ApplicationBuildIdentityTests {

    /** 合法事件构建保持原文并允许当前长度边界。 */
    @Test
    void acceptsRequiredBuilds() {
        for (String build : new String[]{"Release-01", "Release/A", "a".repeat(256)}) {
            /** 当前输入的校验问题。 */
            List<ValidationIssue> issues = new ArrayList<>();
            ApplicationBuildIdentity.validate("1.0", build, issues);
            assertTrue(issues.isEmpty(), () -> "合法构建被拒绝：" + issues);
        }
    }

    /** 构建和版本均必填，边界空白、控制字符和超长均拒绝。 */
    @Test
    void rejectsInvalidIdentities() {
        for (String build : new String[]{null, "", "   ", "\t", "\u00a0", "\u2003", " a", "a ", "\u00a0a", "a\nb", "a".repeat(257)}) {
            /** 当前非法构建的校验问题。 */
            List<ValidationIssue> issues = new ArrayList<>();
            ApplicationBuildIdentity.validate("1.0", build, issues);
            assertFalse(issues.isEmpty());
        }
        for (String version : new String[]{null, "", " ", " 1", "1 ", "a\nb", "a".repeat(129)}) {
            /** 当前非法版本的校验问题。 */
            List<ValidationIssue> issues = new ArrayList<>();
            ApplicationBuildIdentity.validate(version, "build-A", issues);
            assertFalse(issues.isEmpty());
        }
    }
}
