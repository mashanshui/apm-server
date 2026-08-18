package com.shanshui.apmserver.service;

import com.shanshui.apmserver.domain.ValidationIssue;

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
