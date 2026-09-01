package com.shanshui.apmserver;

import com.shanshui.apmserver.domain.AppCreateRequest;
import com.shanshui.apmserver.repository.AppUserRepository;
import com.shanshui.apmserver.repository.ApmAppRepository;
import com.shanshui.apmserver.repository.AppIngestCredentialRepository;
import com.shanshui.apmserver.repository.AppMemberRepository;
import com.shanshui.apmserver.service.AppManagementService;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.UUID;

abstract class AppIngestApiTestSupport {

    @Autowired
    private AppUserRepository ingestTestUserRepository;

    @Autowired
    private ApmAppRepository ingestTestAppRepository;

    @Autowired
    private AppMemberRepository ingestTestMemberRepository;

    @Autowired
    private AppIngestCredentialRepository ingestTestCredentialRepository;

    @Autowired
    private AppManagementService ingestTestAppService;

    private String appKey;
    private UUID appId;

    protected void resetAppCredential(String packageName) {
        ingestTestMemberRepository.deleteAll();
        ingestTestCredentialRepository.deleteAll();
        ingestTestAppRepository.deleteAll();
        var user = ingestTestUserRepository.findByEmailNormalized("test@example.com").orElseThrow();
        var created = ingestTestAppService.create(user.getId(), new AppCreateRequest(packageName));
        appId = created.appId();
        appKey = ingestTestAppService.getIngestCredential(user.getId(), appId).appKey();
    }

    protected String appKey() {
        return appKey;
    }

    protected UUID appId() {
        return appId;
    }
}
