package com.shanshui.apmserver.crash.api;

import com.shanshui.apmserver.telemetry.api.ValidationIssue;

import java.util.List;
import java.util.Map;

/** 其他诊断信号复用的现场校验和脱敏能力。 */
public interface DiagnosticSupport {
    /** 校验通用诊断分区并收集不含正文的错误。 */
    void validate(CrashDiagnostics diagnostics, List<ValidationIssue> issues);

    /** 对通用诊断分区执行存储前脱敏。 */
    CrashDiagnostics sanitize(CrashDiagnostics diagnostics);

    /** 对自由文本执行已有诊断脱敏规则。 */
    String sanitizeText(String value, int maximum);

    /** 对标识字段执行已有长度与控制字符处理。 */
    String sanitizeIdentifier(String value, int maximum);

    /** 使用与其他事件一致的匿名设备摘要。 */
    String hashDeviceId(String value);

    /** 清理公共数值字段。 */
    Map<String, Object> sanitizeMeasurements(Map<String, Object> values);

    /** 清理公共扩展字段。 */
    Map<String, Object> sanitizeAttributes(Map<String, Object> values);
}
