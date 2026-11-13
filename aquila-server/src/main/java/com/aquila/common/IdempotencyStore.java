package com.aquila.common;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class IdempotencyStore {
    private static final int MAX_RAW_KEY_LENGTH = 100;
    private static final String SELECT = """
            SELECT request_path, request_hash, response_json
            FROM sys_idempotency_record
            WHERE user_id = ? AND idempotency_key = ?
            """;

    public String scopedKey(String scope, String rawKey) {
        if (rawKey == null || rawKey.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Idempotency key is required");
        }
        if (rawKey.length() > MAX_RAW_KEY_LENGTH) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Idempotency key is too long");
        }
        return scope + ":" + rawKey;
    }

    public byte[] hash(String value) {
        try {
            return MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    public String find(
            JdbcTemplate jdbc, String userId, String scopedKey, String path, byte[] hash) {
        return find(jdbc, userId, scopedKey, path, hash, false);
    }

    public String findForUpdate(
            JdbcTemplate jdbc, String userId, String scopedKey, String path, byte[] hash) {
        return find(jdbc, userId, scopedKey, path, hash, true);
    }

    public void store(
            JdbcTemplate jdbc,
            String userId,
            String scopedKey,
            String path,
            byte[] hash,
            String responseJson) {
        jdbc.update("""
                INSERT INTO sys_idempotency_record
                    (user_id, idempotency_key, http_method, request_path,
                     request_hash, response_json, created_at)
                VALUES (?, ?, 'POST', ?, ?, ?, NOW(6))
                """, userId, scopedKey, path, hash, responseJson);
    }

    private String find(
            JdbcTemplate jdbc,
            String userId,
            String scopedKey,
            String path,
            byte[] hash,
            boolean forUpdate) {
        String sql = SELECT + (forUpdate ? " FOR UPDATE" : "");
        List<Stored> rows = jdbc.query(sql, (resultSet, rowNumber) -> new Stored(
                resultSet.getString("request_path"),
                resultSet.getBytes("request_hash"),
                resultSet.getString("response_json")), userId, scopedKey);
        if (rows.isEmpty()) {
            return null;
        }
        Stored stored = rows.get(0);
        if (!path.equals(stored.path())
                || stored.hash() == null
                || !MessageDigest.isEqual(hash, stored.hash())) {
            throw new ApiException(
                    HttpStatus.CONFLICT, "Idempotency key is bound to a different request");
        }
        if (stored.responseJson() == null) {
            throw new ApiException(HttpStatus.CONFLICT, "Idempotent request is still in progress");
        }
        return stored.responseJson();
    }

    private record Stored(String path, byte[] hash, String responseJson) {}
}
