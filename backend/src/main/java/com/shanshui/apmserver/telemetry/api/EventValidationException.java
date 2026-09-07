package com.shanshui.apmserver.telemetry.api;

import com.shanshui.apmserver.telemetry.api.ValidationIssue;

import java.util.List;

public class EventValidationException extends RuntimeException {

    private final List<ValidationIssue> issues;

    public EventValidationException(List<ValidationIssue> issues) {
        super(issues.isEmpty() ? "事件校验失败" : issues.get(0).message());
        this.issues = List.copyOf(issues);
    }

    public List<ValidationIssue> getIssues() {
        return issues;
    }
}
