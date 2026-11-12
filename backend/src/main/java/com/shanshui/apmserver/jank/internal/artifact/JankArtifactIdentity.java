package com.shanshui.apmserver.jank.internal.artifact;

import com.shanshui.apmserver.jank.api.InvalidStackArtifactException;
import com.shanshui.apmserver.telemetry.api.ApplicationBuildIdentity;
import com.shanshui.apmserver.telemetry.api.ValidationIssue;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;

/** 产物解析期间固定的应用身份；上传默认 mapping 不放宽产物构建必填约束。 */
record JankArtifactIdentity(/** 应用包名。 */ String packageName,
                            /** 应用版本原值。 */ String appVersion,
                            /** 必填应用构建原值。 */ String buildId) {
    /** 检查 manifest 中的字段类型及共享身份约束，不裁剪有效原值。 */
    static JankArtifactIdentity fromManifest(JsonNode manifest) {
        /** 原始包名、版本和构建节点，严格拒绝类型转换。 */
        JsonNode packageNode = manifest.get("packageName");
        JsonNode versionNode = manifest.get("appVersion");
        JsonNode buildNode = manifest.get("buildId");
        if (packageNode == null || !packageNode.isTextual() || packageNode.asText().isBlank()
                || versionNode == null || !versionNode.isTextual()
                || buildNode == null || !buildNode.isTextual() || buildNode.asText().isBlank()) {
            throw invalid();
        }
        /** 校验后仍保留原文字面值用于精确匹配。 */
        String version = versionNode.asText();
        String build = buildNode.asText();
        /** 共享身份格式问题；构建必填已在上方单独检查。 */
        var issues = new ArrayList<ValidationIssue>();
        ApplicationBuildIdentity.validate(version, build, issues);
        if (!issues.isEmpty()) throw invalid();
        return new JankArtifactIdentity(packageNode.asText(), version, build);
    }

    /** 不回显未经信任的产物内容。 */
    private static InvalidStackArtifactException invalid() {
        return new InvalidStackArtifactException("INVALID_JANK_MANIFEST", "卡顿 manifest 应用身份无效");
    }
}
