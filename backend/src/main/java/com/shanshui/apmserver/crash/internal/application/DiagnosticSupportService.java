package com.shanshui.apmserver.crash.internal.application;

import com.shanshui.apmserver.crash.api.CrashDiagnostics;
import com.shanshui.apmserver.crash.api.DiagnosticSupport;
import com.shanshui.apmserver.telemetry.api.ValidationIssue;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;

/** 暴露 Crash 已验证的通用诊断规则。 */
@Service
public class DiagnosticSupportService implements DiagnosticSupport {
    /** 诊断结构校验器。 */
    private final CrashDiagnosticsValidator validator;
    /** 与 Crash 共享的脱敏器。 */
    private final CrashSanitizer sanitizer;

    /** 使用服务端同一序列化器计算诊断字节数。 */
    public DiagnosticSupportService(ObjectMapper mapper, CrashSanitizer sanitizer) {
        this.validator = new CrashDiagnosticsValidator(mapper);
        this.sanitizer = sanitizer;
    }

    /** 校验通用分区。 */
    @Override
    public void validate(CrashDiagnostics diagnostics, List<ValidationIssue> issues) {
        validator.validate(diagnostics, issues);
    }

    /** 清理通用分区中的敏感文本。 */
    @Override
    public CrashDiagnostics sanitize(CrashDiagnostics diagnostics) {
        return sanitizer.sanitizeDiagnostics(diagnostics);
    }

    /** 清理自由文本。 */
    @Override
    public String sanitizeText(String value, int maximum) {
        return sanitizer.sanitizeText(value, maximum);
    }

    /** 清理身份字段。 */
    @Override
    public String sanitizeIdentifier(String value, int maximum) {
        return sanitizer.sanitizeIdentifier(value, maximum);
    }

    /** 计算匿名设备摘要。 */
    @Override
    public String hashDeviceId(String value) {
        return sanitizer.hashDeviceId(value);
    }

    /** 清理公共数值字段。 */
    @Override
    public Map<String, Object> sanitizeMeasurements(Map<String, Object> values) {
        return sanitizer.sanitizeMeasurements(values);
    }

    /** 清理公共扩展字段。 */
    @Override
    public Map<String, Object> sanitizeAttributes(Map<String, Object> values) {
        return sanitizer.sanitizeAttributes(values);
    }
}
