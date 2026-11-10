package com.aquila.auth;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

public final class TotpSecretCipher {
    private static final int NONCE_BYTES = 12;
    private static final int TAG_BITS = 128;
    private final SecretKeySpec key;
    private final SecureRandom random;

    public TotpSecretCipher(String base64Key) {
        this(base64Key, new SecureRandom());
    }

    TotpSecretCipher(String base64Key, SecureRandom random) {
        if (base64Key == null || base64Key.isBlank()) {
            throw new IllegalStateException("STOCK_TOTP_ENCRYPTION_KEY must be configured");
        }
        byte[] keyBytes;
        try {
            keyBytes = Base64.getDecoder().decode(base64Key);
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException("STOCK_TOTP_ENCRYPTION_KEY must be Base64 encoded", exception);
        }
        if (keyBytes.length != 32) {
            Arrays.fill(keyBytes, (byte) 0);
            throw new IllegalStateException("STOCK_TOTP_ENCRYPTION_KEY must decode to 32 bytes");
        }
        this.key = new SecretKeySpec(keyBytes, "AES");
        Arrays.fill(keyBytes, (byte) 0);
        this.random = random;
    }

    public byte[] encrypt(String secret) {
        byte[] nonce = new byte[NONCE_BYTES];
        random.nextBytes(nonce);
        byte[] plaintext = secret.getBytes(StandardCharsets.US_ASCII);
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, nonce));
            byte[] ciphertext = cipher.doFinal(plaintext);
            byte[] encrypted = new byte[nonce.length + ciphertext.length];
            System.arraycopy(nonce, 0, encrypted, 0, nonce.length);
            System.arraycopy(ciphertext, 0, encrypted, nonce.length, ciphertext.length);
            Arrays.fill(ciphertext, (byte) 0);
            return encrypted;
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("TOTP secret encryption failed", exception);
        } finally {
            Arrays.fill(nonce, (byte) 0);
            Arrays.fill(plaintext, (byte) 0);
        }
    }

    public String decrypt(byte[] encrypted) {
        if (encrypted == null || encrypted.length <= NONCE_BYTES) {
            throw new IllegalArgumentException("Invalid encrypted TOTP secret");
        }
        byte[] nonce = Arrays.copyOfRange(encrypted, 0, NONCE_BYTES);
        byte[] ciphertext = Arrays.copyOfRange(encrypted, NONCE_BYTES, encrypted.length);
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, nonce));
            byte[] plaintext = cipher.doFinal(ciphertext);
            try {
                return new String(plaintext, StandardCharsets.US_ASCII);
            } finally {
                Arrays.fill(plaintext, (byte) 0);
            }
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("TOTP secret decryption failed", exception);
        } finally {
            Arrays.fill(nonce, (byte) 0);
            Arrays.fill(ciphertext, (byte) 0);
        }
    }
}
