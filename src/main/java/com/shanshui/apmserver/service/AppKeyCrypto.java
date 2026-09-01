package com.shanshui.apmserver.service;

import com.shanshui.apmserver.config.AppKeyProperties;
import com.shanshui.apmserver.domain.AppIngestCredential;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;

@Component
public class AppKeyCrypto {

    static final String KEY_PREFIX = "apm_ak_";
    private static final int KEY_RANDOM_BYTES = 32;
    private static final int NONCE_BYTES = 12;
    private static final int GCM_TAG_BITS = 128;

    private final SecretKeySpec encryptionKey;
    private final SecureRandom secureRandom;

    @Autowired
    public AppKeyCrypto(AppKeyProperties properties) {
        this(decodeEncryptionKey(properties.getEncryptionKey()), new SecureRandom());
    }

    AppKeyCrypto(byte[] encryptionKey, SecureRandom secureRandom) {
        if (encryptionKey == null || encryptionKey.length != 32) {
            throw new IllegalArgumentException("APM_APP_KEY_ENCRYPTION_KEY 必须是 Base64 编码的 32 字节值");
        }
        this.encryptionKey = new SecretKeySpec(encryptionKey.clone(), "AES");
        this.secureRandom = secureRandom;
    }

    public AppKeyMaterial generate(java.util.UUID appId, String packageName) {
        byte[] random = new byte[KEY_RANDOM_BYTES];
        byte[] nonce = new byte[NONCE_BYTES];
        secureRandom.nextBytes(random);
        secureRandom.nextBytes(nonce);
        String appKey = KEY_PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(random);
        return new AppKeyMaterial(appKey, digest(appKey),
                encrypt(appKey, nonce, appId, packageName), nonce);
    }

    public byte[] digest(String appKey) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(appKey.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("JVM 不支持 SHA-256", ex);
        }
    }

    public String decrypt(AppIngestCredential credential, String packageName) {
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, encryptionKey,
                    new GCMParameterSpec(GCM_TAG_BITS, credential.getKeyNonce()));
            cipher.updateAAD(aad(credential.getAppId(), packageName));
            return new String(cipher.doFinal(credential.getKeyCiphertext()), StandardCharsets.UTF_8);
        } catch (AEADBadTagException ex) {
            throw new AppCredentialDecryptionException(ex);
        } catch (GeneralSecurityException ex) {
            throw new AppCredentialDecryptionException(ex);
        }
    }

    private byte[] encrypt(String appKey, byte[] nonce, java.util.UUID appId, String packageName) {
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, encryptionKey, new GCMParameterSpec(GCM_TAG_BITS, nonce));
            cipher.updateAAD(aad(appId, packageName));
            return cipher.doFinal(appKey.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException ex) {
            throw new AppCredentialCreationException(ex);
        }
    }

    private static byte[] aad(java.util.UUID appId, String packageName) {
        return (appId + "\n" + packageName).getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] decodeEncryptionKey(String encoded) {
        if (encoded == null || encoded.isBlank()) {
            throw new IllegalArgumentException("缺少 APM_APP_KEY_ENCRYPTION_KEY");
        }
        try {
            byte[] decoded = Base64.getDecoder().decode(encoded.trim());
            if (decoded.length != 32) {
                throw new IllegalArgumentException("APM_APP_KEY_ENCRYPTION_KEY 必须解码为 32 字节");
            }
            return decoded;
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("APM_APP_KEY_ENCRYPTION_KEY 必须是 Base64 编码的 32 字节值", ex);
        }
    }
}
