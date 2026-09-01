package com.shanshui.apmserver.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.Arrays;

@Entity
@Table(name = "app_ingest_credential")
public class AppIngestCredential {

    @Id
    @Column(name = "app_id", nullable = false)
    private java.util.UUID appId;

    @Column(name = "key_digest", nullable = false, length = 32)
    private byte[] keyDigest;

    @Column(name = "key_ciphertext", nullable = false)
    private byte[] keyCiphertext;

    @Column(name = "key_nonce", nullable = false, length = 12)
    private byte[] keyNonce;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected AppIngestCredential() {
    }

    public AppIngestCredential(java.util.UUID appId, byte[] keyDigest,
                                   byte[] keyCiphertext, byte[] keyNonce, Instant createdAt) {
        this.appId = appId;
        this.keyDigest = copy(keyDigest);
        this.keyCiphertext = copy(keyCiphertext);
        this.keyNonce = copy(keyNonce);
        this.createdAt = createdAt;
    }

    public java.util.UUID getAppId() {
        return appId;
    }

    public byte[] getKeyDigest() {
        return copy(keyDigest);
    }

    public byte[] getKeyCiphertext() {
        return copy(keyCiphertext);
    }

    public byte[] getKeyNonce() {
        return copy(keyNonce);
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    private static byte[] copy(byte[] value) {
        return value == null ? null : Arrays.copyOf(value, value.length);
    }
}
