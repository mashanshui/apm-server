package com.shanshui.apmserver.identity.internal.application;

import java.util.Arrays;

record AppKeyMaterial(String appKey, byte[] digest, byte[] ciphertext, byte[] nonce) {

    AppKeyMaterial {
        digest = copy(digest);
        ciphertext = copy(ciphertext);
        nonce = copy(nonce);
    }

    @Override
    public byte[] digest() {
        return copy(digest);
    }

    @Override
    public byte[] ciphertext() {
        return copy(ciphertext);
    }

    @Override
    public byte[] nonce() {
        return copy(nonce);
    }

    private static byte[] copy(byte[] value) {
        return Arrays.copyOf(value, value.length);
    }
}
