package com.shanshui.apmserver.agentquery.internal.web;

import com.shanshui.apmserver.platform.api.QueryParams;
import com.shanshui.apmserver.platform.api.QueryValidationException;
import org.springframework.util.MultiValueMap;

import java.util.Set;

/** Agent 查询白名单与列表上限，在业务服务的二次校验之前执行。 */
final class AgentQueryParameters {

    private AgentQueryParameters() {
    }

    /** 拒绝未知或重复参数，尤其不接受客户端提供的 appId。 */
    static void validate(MultiValueMap<String, String> query, Set<String> allowed) {
        for (var entry : query.entrySet()) {
            if (!allowed.contains(entry.getKey()) || entry.getValue().size() != 1) {
                throw new QueryValidationException("INVALID_FILTER", "查询参数不在允许的白名单中或重复: "
                        + entry.getKey(), 400);
            }
        }
    }

    /** Agent 列表默认 20、最多 100；业务服务仍可实施更严格的限制。 */
    static void limit(QueryParams params) {
        if (params.getLimit() == null) params.setLimit(20);
        if (params.getLimit() < 1 || params.getLimit() > 100) {
            throw new QueryValidationException("INVALID_LIMIT", "limit 必须在 1 到 100 之间", 400);
        }
    }

    /** 只接受明确的布尔文本，不把任意输入解释为 false。 */
    static Boolean foreground(String value) {
        if (value == null || value.isBlank()) return null;
        if ("true".equalsIgnoreCase(value)) return true;
        if ("false".equalsIgnoreCase(value)) return false;
        throw new QueryValidationException("INVALID_FOREGROUND", "foreground 必须是 true 或 false", 400);
    }
}
