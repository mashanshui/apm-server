package com.shanshui.apmserver.identity.internal.application;

import com.shanshui.apmserver.identity.api.IngestAccessConfiguration;
import com.shanshui.apmserver.identity.api.AppAuthenticationUnavailableException;
import com.shanshui.apmserver.identity.api.AppKeyAuthentication;
import com.shanshui.apmserver.identity.api.AuthenticatedApp;
import com.shanshui.apmserver.identity.api.InvalidAppKeyException;
import com.shanshui.apmserver.identity.internal.persistence.AppCredentialIdentity;
import com.shanshui.apmserver.identity.internal.persistence.AppIngestCredentialRepository;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

import java.util.regex.Pattern;

@Service
public class AppKeyAuthenticator implements AppKeyAuthentication {

    private static final Pattern APP_KEY_FORMAT = Pattern.compile("^apm_ak_[A-Za-z0-9_-]{43}$");

    private final IngestAccessConfiguration properties;
    private final AppIngestCredentialRepository credentialRepository;
    private final AppKeyCrypto keyCrypto;

    public AppKeyAuthenticator(IngestAccessConfiguration properties,
                                   AppIngestCredentialRepository credentialRepository,
                                   AppKeyCrypto keyCrypto) {
        this.properties = properties;
        this.credentialRepository = credentialRepository;
        this.keyCrypto = keyCrypto;
    }

    @Override
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
