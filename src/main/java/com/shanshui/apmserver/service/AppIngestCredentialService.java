package com.shanshui.apmserver.service;

import com.shanshui.apmserver.domain.AppIngestCredential;
import com.shanshui.apmserver.domain.AppIngestCredentialResponse;
import com.shanshui.apmserver.repository.AppCredentialIdentity;
import com.shanshui.apmserver.repository.AppIngestCredentialRepository;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

import java.time.Instant;

@Service
public class AppIngestCredentialService {

    private static final int MAX_GENERATION_ATTEMPTS = 5;

    private final AppIngestCredentialRepository credentialRepository;
    private final AppKeyCrypto keyCrypto;

    public AppIngestCredentialService(AppIngestCredentialRepository credentialRepository,
                                          AppKeyCrypto keyCrypto) {
        this.credentialRepository = credentialRepository;
        this.keyCrypto = keyCrypto;
    }

    public AppIngestCredential create(java.util.UUID appId, String packageName, Instant createdAt) {
        try {
            for (int attempt = 0; attempt < MAX_GENERATION_ATTEMPTS; attempt++) {
                AppKeyMaterial material = keyCrypto.generate(appId, packageName);
                if (credentialRepository.existsByKeyDigest(material.digest())) {
                    continue;
                }
                try {
                    return credentialRepository.saveAndFlush(new AppIngestCredential(
                            appId, material.digest(), material.ciphertext(), material.nonce(), createdAt));
                } catch (org.springframework.dao.DataIntegrityViolationException ex) {
                    if (attempt + 1 == MAX_GENERATION_ATTEMPTS) {
                        throw ex;
                    }
                }
            }
            throw new AppCredentialCreationException(
                    new IllegalStateException("App Key 摘要连续冲突"));
        } catch (AppCredentialCreationException ex) {
            throw ex;
        } catch (DataAccessException ex) {
            throw new AppCredentialCreationException(ex);
        }
    }

    public String packageName(java.util.UUID appId) {
        try {
            return credentialRepository.findIdentityByAppId(appId)
                    .map(AppCredentialIdentity::getPackageName)
                    .orElseThrow(() -> new AppCredentialCreationException(
                            new IllegalStateException("应用缺少上报凭据")));
        } catch (AppCredentialCreationException ex) {
            throw ex;
        } catch (DataAccessException ex) {
            throw new AppCredentialCreationException(ex);
        }
    }

    public AppIngestCredentialResponse read(java.util.UUID appId) {
        try {
            AppIngestCredential credential = credentialRepository.findById(appId)
                    .orElseThrow(AppNotFoundException::new);
            String packageName = credentialRepository.findIdentityByAppId(appId)
                    .map(AppCredentialIdentity::getPackageName)
                    .orElseThrow(AppNotFoundException::new)
                    ;
            return new AppIngestCredentialResponse(appId, packageName, keyCrypto.decrypt(credential, packageName));
        } catch (AppNotFoundException | AppCredentialDecryptionException ex) {
            throw ex;
        } catch (DataAccessException ex) {
            throw new AppCredentialDecryptionException(ex);
        }
    }
}
