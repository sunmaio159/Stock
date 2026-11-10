package com.aquila.auth;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.OptionalLong;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

public final class TotpService {
    private static final String BASE32 = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
    private static final long PERIOD_SECONDS = 30;
    private static final int DIGITS = 6;

    private final SecureRandom random;

    public TotpService() {
        this(new SecureRandom());
    }

    TotpService(SecureRandom random) {
        this.random = random;
    }

    public String generateSecret() {
        byte[] secret = new byte[20];
        random.nextBytes(secret);
        try {
            return encodeBase32(secret);
        } finally {
            java.util.Arrays.fill(secret, (byte) 0);
        }
    }

    public String generateCode(String secret, Instant instant) {
        byte[] secretBytes = decodeBase32(secret);
        try {
            return codeAtStep(secretBytes, Math.floorDiv(instant.getEpochSecond(), PERIOD_SECONDS));
        } finally {
            java.util.Arrays.fill(secretBytes, (byte) 0);
        }
    }

    public boolean verifyCode(String secret, String code, Instant instant) {
        return matchingStep(secret, code, instant, Long.MIN_VALUE).isPresent();
    }

    public OptionalLong matchingStep(String secret, String code, Instant instant, long lastUsedStep) {
        if (code == null || !code.matches("\\d{6}")) {
            return OptionalLong.empty();
        }
        byte[] secretBytes;
        try {
            secretBytes = decodeBase32(secret);
        } catch (IllegalArgumentException exception) {
            return OptionalLong.empty();
        }
        long currentStep = Math.floorDiv(instant.getEpochSecond(), PERIOD_SECONDS);
        long matched = Long.MIN_VALUE;
        for (long step = currentStep - 1; step <= currentStep + 1; step++) {
            if (step > lastUsedStep && constantTimeEquals(code, codeAtStep(secretBytes, step))) {
                matched = Math.max(matched, step);
            }
        }
        java.util.Arrays.fill(secretBytes, (byte) 0);
        return matched == Long.MIN_VALUE ? OptionalLong.empty() : OptionalLong.of(matched);
    }

    public String otpauthUri(String username, String secret) {
        String label = java.net.URLEncoder.encode("Aquila Stock:" + username, StandardCharsets.UTF_8);
        return "otpauth://totp/" + label + "?secret=" + secret
                + "&issuer=Aquila%20Stock&algorithm=SHA1&digits=" + DIGITS + "&period=" + PERIOD_SECONDS;
    }

    static String encodeBase32(byte[] value) {
        StringBuilder result = new StringBuilder((value.length * 8 + 4) / 5);
        int buffer = 0;
        int bits = 0;
        for (byte item : value) {
            buffer = (buffer << 8) | (item & 0xff);
            bits += 8;
            while (bits >= 5) {
                result.append(BASE32.charAt((buffer >> (bits - 5)) & 0x1f));
                bits -= 5;
            }
        }
        if (bits > 0) {
            result.append(BASE32.charAt((buffer << (5 - bits)) & 0x1f));
        }
        return result.toString();
    }

    static byte[] decodeBase32(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Invalid Base32 value");
        }
        String normalized = value.replace("-", "").replace(" ", "").toUpperCase(java.util.Locale.ROOT);
        byte[] output = new byte[normalized.length() * 5 / 8];
        int buffer = 0;
        int bits = 0;
        int index = 0;
        for (int position = 0; position < normalized.length(); position++) {
            int digit = BASE32.indexOf(normalized.charAt(position));
            if (digit < 0) {
                throw new IllegalArgumentException("Invalid Base32 value");
            }
            buffer = (buffer << 5) | digit;
            bits += 5;
            if (bits >= 8) {
                output[index++] = (byte) ((buffer >> (bits - 8)) & 0xff);
                bits -= 8;
            }
        }
        if (index != output.length) {
            throw new IllegalArgumentException("Invalid Base32 value");
        }
        return output;
    }

    private String codeAtStep(byte[] secret, long step) {
        try {
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(secret, "HmacSHA1"));
            byte[] counter = new byte[Long.BYTES];
            for (int index = 0; index < Long.BYTES; index++) {
                counter[Long.BYTES - 1 - index] = (byte) (step >>> (index * 8));
            }
            byte[] hash = mac.doFinal(counter);
            int offset = hash[hash.length - 1] & 0x0f;
            int binary = ((hash[offset] & 0x7f) << 24)
                    | ((hash[offset + 1] & 0xff) << 16)
                    | ((hash[offset + 2] & 0xff) << 8)
                    | (hash[offset + 3] & 0xff);
            java.util.Arrays.fill(counter, (byte) 0);
            java.util.Arrays.fill(hash, (byte) 0);
            return String.format(java.util.Locale.ROOT, "%06d", binary % 1_000_000);
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("TOTP algorithm is unavailable", exception);
        }
    }

    private boolean constantTimeEquals(String left, String right) {
        return java.security.MessageDigest.isEqual(
                left.getBytes(StandardCharsets.US_ASCII),
                right.getBytes(StandardCharsets.US_ASCII));
    }
}
