package com.shanshui.apmserver.symbol.api;

import java.util.List;
import java.util.UUID;

/** 后台仅轮转读取活动版本元数据，不扫描 mapping 内容或遥测数据。 */
public interface SymbolBuildIndex {
    /** 按唯一 symbolId 升序续读，每次最多 100。 */
    List<Build> activeAfter(UUID afterSymbolId, int limit);

    /** 仅用于持久化对账的活动来源身份。 */
    record Build(
            /** 当前活动符号表身份。 */ UUID symbolId,
            /** 所属应用。 */ UUID appId,
            /** 精确版本。 */ String appVersion) {}
}
