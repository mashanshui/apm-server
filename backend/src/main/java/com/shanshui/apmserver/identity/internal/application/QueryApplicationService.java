package com.shanshui.apmserver.identity.internal.application;

import com.shanshui.apmserver.identity.api.AppNotFoundException;
import com.shanshui.apmserver.identity.api.QueryApplicationInfo;
import com.shanshui.apmserver.identity.api.QueryApplicationLookup;
import com.shanshui.apmserver.identity.internal.domain.ApmApp;
import com.shanshui.apmserver.identity.internal.persistence.ApmAppRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/** 只公开 Agent 读取应用所需的字段。 */
@Service
public class QueryApplicationService implements QueryApplicationLookup {

    private final ApmAppRepository apps;

    /** 注入应用实体仓库。 */
    public QueryApplicationService(ApmAppRepository apps) { this.apps = apps; }

    /** Token 所属应用已删除时按资源不存在处理。 */
    @Override
    @Transactional(readOnly = true)
    public QueryApplicationInfo get(UUID appId) {
        ApmApp app = apps.findById(appId).orElseThrow(AppNotFoundException::new);
        return new QueryApplicationInfo(app.getAppId(), app.getName(), app.getPackageName());
    }
}
