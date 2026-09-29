package com.shanshui.apmserver.identity.api;

import java.util.UUID;

/** Agent 只读入口可见的当前应用基本信息。 */
public record QueryApplicationInfo(UUID appId, String name, String packageName) {
}
