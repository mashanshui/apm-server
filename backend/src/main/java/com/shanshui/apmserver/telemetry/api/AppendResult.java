package com.shanshui.apmserver.telemetry.api;

/** 一次幂等写入中新增和重复的事件数量。 */
public record AppendResult(int accepted, int duplicate) {

    public AppendResult {
        if (accepted < 0 || duplicate < 0) {
            throw new IllegalArgumentException("写入计数不能为负数");
        }
    }
}
