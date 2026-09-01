package com.shanshui.apmserver.service;

import com.shanshui.apmserver.config.AppKeyProperties;
import com.shanshui.apmserver.domain.AppIngestCredential;
import com.shanshui.apmserver.TestAppIds;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Base64;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AppKeyCryptoTests {

    private static final String MASTER_KEY = Base64.getEncoder().encodeToString(
            "0123456789abcdef0123456789abcdef".getBytes(java.nio.charset.StandardCharsets.UTF_8));

    @Test
    void generatesUniqueUrlSafeKeysAndRestoresAcrossInstances() {
        AppKeyCrypto first = crypto(MASTER_KEY);
        Set<String> keys = new HashSet<>();
        AppKeyMaterial retained = null;
        for (int index = 0; index < 100; index++) {
            AppKeyMaterial material = first.generate(TestAppIds.id("app-a"), "com.example.app");
            assertTrue(material.appKey().matches("apm_ak_[A-Za-z0-9_-]{43}"));
            assertEquals(32, material.digest().length);
            assertEquals(12, material.nonce().length);
            assertTrue(keys.add(material.appKey()));
            retained = material;
        }

        AppIngestCredential stored = credential(retained, "app-a", "com.example.app");
        assertEquals(retained.appKey(), crypto(MASTER_KEY).decrypt(stored, "com.example.app"));
    }

    @Test
    void detectsCiphertextAadAndMasterKeyChanges() {
        AppKeyCrypto crypto = crypto(MASTER_KEY);
        AppKeyMaterial material = crypto.generate(TestAppIds.id("app-a"), "com.example.app");
        byte[] tampered = material.ciphertext();
        tampered[0] ^= 1;

        AppCredentialDecryptionException exception = assertThrows(AppCredentialDecryptionException.class,
                () -> crypto.decrypt(new AppIngestCredential(TestAppIds.id("app-a"),
                        material.digest(), tampered, material.nonce(), Instant.now()), "com.example.app"));
        assertFalse(exception.getMessage().contains(material.appKey()));
        assertThrows(AppCredentialDecryptionException.class,
                () -> crypto.decrypt(credential(material, "app-b", "com.example.app"), "com.example.app"));
        assertThrows(AppCredentialDecryptionException.class,
                () -> crypto.decrypt(credential(material, "app-a", "com.other.app"), "com.other.app"));

        String otherKey = Base64.getEncoder().encodeToString(
                "abcdef0123456789abcdef0123456789".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertNotEquals(MASTER_KEY, otherKey);
        assertThrows(AppCredentialDecryptionException.class,
                () -> crypto(otherKey).decrypt(credential(material, "app-a", "com.example.app"), "com.example.app"));
    }

    @Test
    void rejectsMissingMalformedAndWrongLengthMasterKeys() {
        assertThrows(IllegalArgumentException.class, () -> crypto(null));
        assertThrows(IllegalArgumentException.class, () -> crypto("not-base64"));
        assertThrows(IllegalArgumentException.class,
                () -> crypto(Base64.getEncoder().encodeToString(new byte[31])));
    }

    private AppKeyCrypto crypto(String encodedKey) {
        AppKeyProperties properties = new AppKeyProperties();
        properties.setEncryptionKey(encodedKey);
        return new AppKeyCrypto(properties);
    }

    private AppIngestCredential credential(AppKeyMaterial material, String appId, String packageName) {
        return new AppIngestCredential(TestAppIds.id(appId), material.digest(), material.ciphertext(),
                material.nonce(), Instant.now());
    }
}
