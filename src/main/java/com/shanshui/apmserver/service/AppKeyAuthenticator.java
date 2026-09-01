package com.shanshui.apmserver.service;

import com.shanshui.apmserver.config.IngestProperties;
import com.shanshui.apmserver.domain.AuthenticatedApp;
import com.shanshui.apmserver.repository.AppCredentialIdentity;
import com.shanshui.apmserver.repository.AppIngestCredentialRepository;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

import java.util.regex.Pattern;

@Service
public class AppKeyAuthenticator {

    private static final Pattern APP_KEY_FORMAT = Pattern.compile("^apm_ak_[A-Za-z0-9_-]{43}$");

    private final IngestProperties properties;
    private final AppIngestCredentialRepository credentialRepository;
    private final AppKeyCrypto keyCrypto;

    public AppKeyAuthenticator(IngestProperties properties,
                                   AppIngestCredentialRepository credentialRepository,
                                   AppKeyCrypto keyCrypto) {
        this.properties = properties;
        this.credentialRepository = credentialRepository;
        this.keyCrypto = keyCrypto;
    }

    public AuthenticatedApp authenticate(String appKey) {
        if (!properties.isEnabled() || appKey == null || !APP_KEY_FORMAT.matcher(appKey).matches()) {
            throw new InvalidAppKeyException();
        }
        try {
            AppCredentialIdentity identity = credentialRepository.findIdentityByKeyDigest(keyCrypto.digest(appKey))
                    .orElseThrow(InvalidAppKeyException::new);
            return new AuthenticatedApp(identity.getAppId(), identity.getPackageName());
        } catch (InvalidAppKeyException ex) {
            throw ex;
        } catch (DataAccessException ex) {
            throw new AppAuthenticationUnavailableException(ex);
        }
    }
}
