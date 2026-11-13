package com.aquila.masterdata;

import com.aquila.common.AdminContext;
import com.aquila.common.ApiException;
import com.aquila.common.IdempotencyStore;
import com.aquila.config.MasterDataLimits;
import com.aquila.watchlist.CapacityGuard;
import com.aquila.watchlist.CapacityPolicy;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
@ConditionalOnProperty(name = "aquila.database.enabled", havingValue = "true")
public class ImportService {
    private static final int RAW_TEXT_LIMIT = 512;
    private static final String SCOPE = "IMPORT_COMMIT";

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final AdminContext admin;
    private final MasterDataLimits limits;
    private final CapacityGuard capacityGuard;
    private final IdempotencyStore idempotency;
    private final ObjectMapper objectMapper;

    public ImportService(
            JdbcTemplate jdbc,
            TransactionTemplate transactions,
            AdminContext admin,
            MasterDataLimits limits,
            CapacityGuard capacityGuard,
            IdempotencyStore idempotency,
            ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.admin = admin;
        this.limits = limits;
        this.capacityGuard = capacityGuard;
        this.idempotency = idempotency;
        this.objectMapper = objectMapper;
    }

    public ImportPreview preview(String fileName, String content) {
        String userId = admin.requireUserId();
        String text = content == null ? "" : content;
        long bytes = text.getBytes(StandardCharsets.UTF_8).length;
        if (bytes > limits.getImportUploadBytes()) {
            throw new ApiException(HttpStatus.PAYLOAD_TOO_LARGE, "Import file exceeds the frozen size limit");
        }

        List<String> lines = splitLines(text);
        if (lines.size() > limits.getImportRows()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Import file exceeds the frozen row limit");
        }

        List<ImportRow> rows = new ArrayList<>();
        for (int index = 0; index < lines.size(); index++) {
            rows.add(ImportRow.parse(index + 1, lines.get(index)));
        }
        int errorCount = (int) rows.stream().filter(row -> !row.valid()).count();
        String batchId = UUID.randomUUID().toString();
        String safeFileName = fileName == null || fileName.isBlank() ? "watchlist.txt" : fileName;
        if (safeFileName.length() > 255) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Import file name is too long");
        }

        transactions.executeWithoutResult(status -> {
            jdbc.update("""
                    INSERT INTO sys_import_batch
                        (batch_id, user_id, kind, file_name, status,
                         row_count, error_count, created_at)
                    VALUES (?, ?, 'WATCHLIST', ?, 'PREVIEW', ?, ?, NOW(6))
                    """, batchId, userId, safeFileName, rows.size(), errorCount);
            for (ImportRow row : rows) {
                jdbc.update("""
                        INSERT INTO sys_import_row
                            (row_id, batch_id, row_no, raw_text, normalized_code, error_code)
                        VALUES (?, ?, ?, ?, ?, ?)
                        """, UUID.randomUUID().toString(), batchId, row.rowNo(),
                        truncate(row.rawText()), row.normalizedCode(), row.errorCode());
            }
        });

        return new ImportPreview(batchId, safeFileName, "PREVIEW", rows.size(), errorCount, rows);
    }

    public ImportPreview batch(String batchId) {
        String userId = admin.requireUserId();
        List<ImportPreview> found = jdbc.query("""
                SELECT batch_id, file_name, status, row_count, error_count
                FROM sys_import_batch WHERE batch_id = ? AND user_id = ?
                """, (resultSet, rowNumber) -> new ImportPreview(
                        resultSet.getString("batch_id"),
                        resultSet.getString("file_name"),
                        resultSet.getString("status"),
                        resultSet.getInt("row_count"),
                        resultSet.getInt("error_count"),
                        List.of()), batchId, userId);
        if (found.isEmpty()) {
            throw new ApiException(HttpStatus.NOT_FOUND, "Import batch not found");
        }
        ImportPreview header = found.get(0);
        List<ImportRow> rows = jdbc.query("""
                SELECT row_no, raw_text, normalized_code, error_code
                FROM sys_import_row WHERE batch_id = ? ORDER BY row_no
                """, (resultSet, rowNumber) -> new ImportRow(
                        resultSet.getInt("row_no"),
                        resultSet.getString("raw_text"),
                        resultSet.getString("normalized_code"),
                        resultSet.getString("error_code")), batchId);
        return new ImportPreview(
                header.batchId(), header.fileName(), header.status(),
                header.rowCount(), header.errorCount(), rows);
    }

    public ImportResult commit(String batchId, String idempotencyKey) {
        String userId = admin.requireUserId();
        String scopedKey = idempotency.scopedKey(SCOPE, idempotencyKey);
        String path = "/api/imports/" + batchId + "/commit";
        byte[] hash = idempotency.hash(batchId + "|" + idempotencyKey);
        capacityGuard.ensureLockRow(jdbc, userId);
        return transactions.execute(status -> {
            String stored = idempotency.find(jdbc, userId, scopedKey, path, hash);
            if (stored != null) {
                return decodeResult(stored);
            }

            List<BatchState> batches = jdbc.query("""
                    SELECT batch_id, file_name, status FROM sys_import_batch
                    WHERE batch_id = ? AND user_id = ? FOR UPDATE
                    """, (resultSet, rowNumber) -> new BatchState(
                            resultSet.getString("batch_id"),
                            resultSet.getString("file_name"),
                            resultSet.getString("status")), batchId, userId);
            if (batches.isEmpty()) {
                throw new ApiException(HttpStatus.NOT_FOUND, "Import batch not found");
            }
            BatchState batch = batches.get(0);
            if (!"PREVIEW".equals(batch.status())) {
                String concurrent = idempotency.findForUpdate(jdbc, userId, scopedKey, path, hash);
                if (concurrent != null) {
                    return decodeResult(concurrent);
                }
                throw new ApiException(HttpStatus.CONFLICT, "Import batch is no longer in preview");
            }

            List<ImportRow> rows = jdbc.query("""
                    SELECT row_no, raw_text, normalized_code, error_code
                    FROM sys_import_row WHERE batch_id = ? ORDER BY row_no
                    """, (resultSet, rowNumber) -> new ImportRow(
                            resultSet.getInt("row_no"),
                            resultSet.getString("raw_text"),
                            resultSet.getString("normalized_code"),
                            resultSet.getString("error_code")), batchId);

            Set<String> distinct = capacityGuard.lockDistinct(jdbc, userId);
            String groupId = findOrCreateGroup(userId, batch.fileName());
            int committed = 0;
            for (ImportRow row : rows) {
                if (!row.valid() || row.normalizedCode() == null) {
                    continue;
                }
                String code = row.normalizedCode();
                if (!securityExists(code) || itemExists(groupId, code)) {
                    continue;
                }
                if (!distinct.contains(code)
                        && CapacityPolicy.wouldExceed(
                                distinct.size(), limits.getWatchlistCapacity(), false)) {
                    throw new ApiException(HttpStatus.CONFLICT, "Watchlist capacity reached");
                }
                insertItem(groupId, code);
                distinct.add(code);
                committed++;
            }

            ImportResult result = new ImportResult(batchId, committed, "COMMITTED");
            jdbc.update("""
                    UPDATE sys_import_batch
                    SET status = 'COMMITTED', idempotency_key = ?, committed_at = NOW(6)
                    WHERE batch_id = ?
                    """, scopedKey, batchId);
            idempotency.store(jdbc, userId, scopedKey, path, hash, encodeResult(result));
            return result;
        });
    }

    private ImportResult decodeResult(String json) {
        try {
            JsonNode node = objectMapper.readTree(json);
            if (!SCOPE.equals(node.path("type").asText())
                    || node.get("batchId") == null
                    || node.get("committedRows") == null
                    || node.get("status") == null) {
                throw new ApiException(
                        HttpStatus.CONFLICT, "Idempotency record belongs to another operation");
            }
            return new ImportResult(
                    node.get("batchId").asText(),
                    node.get("committedRows").asInt(),
                    node.get("status").asText());
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Stored idempotency response is not readable", exception);
        }
    }

    private String encodeResult(ImportResult result) {
        try {
            return objectMapper.writeValueAsString(Map.of(
                    "type", SCOPE,
                    "batchId", result.batchId(),
                    "committedRows", result.committedRows(),
                    "status", result.status()));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Cannot encode idempotency response", exception);
        }
    }

    private String findOrCreateGroup(String userId, String fileName) {
        String name = fileName == null || fileName.isBlank() ? "Imported" : fileName;
        if (name.length() > 80) {
            name = name.substring(0, 80);
        }
        List<String> existing = jdbc.queryForList("""
                SELECT group_id FROM m1_watchlist_group
                WHERE user_id = ? AND group_name = ?
                """, String.class, userId, name);
        if (!existing.isEmpty()) {
            return existing.get(0);
        }
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM m1_watchlist_group WHERE user_id = ?",
                Integer.class, userId);
        int size = count == null ? 0 : count;
        if (size >= limits.getMaxGroups()) {
            throw new ApiException(HttpStatus.CONFLICT, "Watchlist group limit reached");
        }
        String groupId = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO m1_watchlist_group
                    (group_id, user_id, group_name, sort_order, version, created_at, updated_at)
                VALUES (?, ?, ?, ?, 0, NOW(6), NOW(6))
                """, groupId, userId, name, size);
        return groupId;
    }

    private boolean securityExists(String securityCode) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM m2_security WHERE security_code = ?",
                Integer.class, securityCode);
        return count != null && count > 0;
    }

    private boolean itemExists(String groupId, String securityCode) {
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM m1_watchlist_item
                WHERE group_id = ? AND security_code = ?
                """, Integer.class, groupId, securityCode);
        return count != null && count > 0;
    }

    private void insertItem(String groupId, String securityCode) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM m1_watchlist_item WHERE group_id = ?",
                Integer.class, groupId);
        jdbc.update("""
                INSERT INTO m1_watchlist_item
                    (item_id, group_id, security_code, sort_order, created_at)
                VALUES (?, ?, ?, ?, NOW(6))
                """, UUID.randomUUID().toString(), groupId, securityCode,
                count == null ? 0 : count);
    }

    private static List<String> splitLines(String text) {
        if (text.isEmpty()) {
            return List.of();
        }
        List<String> lines = new ArrayList<>(Arrays.asList(text.split("\\R", -1)));
        if (!lines.isEmpty() && lines.get(lines.size() - 1).isEmpty()) {
            lines.remove(lines.size() - 1);
        }
        return lines;
    }

    private static String truncate(String value) {
        String text = value == null ? "" : value;
        return text.length() > RAW_TEXT_LIMIT ? text.substring(0, RAW_TEXT_LIMIT) : text;
    }

    private record BatchState(String batchId, String fileName, String status) {}
}
