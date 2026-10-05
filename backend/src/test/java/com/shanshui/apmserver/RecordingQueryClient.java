package com.shanshui.apmserver;

import com.shanshui.apmserver.platform.api.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** 测试记录完整 HTTP 响应字节与端到端耗时，区别于 query_log 的内部 result_bytes。 */
final class RecordingQueryClient extends ClickHouseHttpClient {
    /** 每次成功查询只保存资源指标与 SQL，不保存敏感事实。 */
    private final List<String> records = new ArrayList<>();
    /** 测试实例共享普通客户端实现。 */
    RecordingQueryClient(ClickHouseProperties properties) { super(properties); }
    /** 完整读取后才记录成功，超时或超限不能成为性能成功样本。 */
    @Override public String executeQuery(String query, QueryBudget budget) {
        long start=System.nanoTime();
        String body=super.executeQuery(query,budget);
        records.add("HTTP bytes="+body.getBytes(StandardCharsets.UTF_8).length+" elapsed_ms="+(System.nanoTime()-start)/1_000_000.0+" SQL="+query);
        return body;
    }
    /** 性能阶段完成后输出末尾有界记录。 */
    String last(int count) { return String.join("\n",records.subList(Math.max(0,records.size()-count),records.size())); }
}
