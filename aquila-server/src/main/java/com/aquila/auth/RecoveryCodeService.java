package com.aquila.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;

public final class RecoveryCodeService {
    private static final int CODES_PER_BATCH = 10;
    private static final int RANDOM_BYTES = 16;

    private final SecureRandom random;

    public RecoveryCodeService() {
        this(new SecureRandom());
    }

    RecoveryCodeService(SecureRandom random) {
        this.random = random;
    }

    public List<IssuedCode> generateBatch() {
        List<IssuedCode> codes = new ArrayList<>(CODES_PER_BATCH);
        for (int index = 0; index < CODES_PER_BATCH; index++) {
            byte[] randomBytes = new byte[RANDOM_BYTES];
            random.nextBytes(randomBytes);
            String code = TotpService.encodeBase32(randomBytes);
            codes.add(new IssuedCode(code, hashCode(code)));
            java.util.Arrays.fill(randomBytes, (byte) 0);
        }
        return List.copyOf(codes);
    }

    public byte[] hashCode(String code) {
        String normalized = normalize(code);
        byte[] decoded = TotpService.decodeBase32(normalized);
        if (decoded.length != RANDOM_BYTES) {
            java.util.Arrays.fill(decoded, (byte) 0);
            throw new IllegalArgumentException("Invalid recovery code");
        }
        try {
            return MessageDigest.getInstance("SHA-256").digest(normalized.getBytes(StandardCharsets.US_ASCII));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        } finally {
            java.util.Arrays.fill(decoded, (byte) 0);
        }
    }

    private String normalize(String code) {
        if (code == null) {
            throw new IllegalArgumentException("Invalid recovery code");
        }
        return code.replace("-", "").replace(" ", "").toUpperCase(java.util.Locale.ROOT);
    }

    public record IssuedCode(String clearCode, byte[] codeHash) {
        @Override
        public String toString() {
            return "IssuedCode[credentials=REDACTED]";
        }
    }
}
