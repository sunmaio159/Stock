package com.aquila.masterdata;

import com.aquila.common.AdminApi;
import com.aquila.common.Result;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
@ConditionalOnProperty(name = "aquila.database.enabled", havingValue = "true")
public class ImportController {
    private final ImportService imports;
    private final AdminApi api;

    public ImportController(ImportService imports, AdminApi api) {
        this.imports = imports;
        this.api = api;
    }

    @PostMapping("/imports/preview")
    public Result<ImportPreview> preview(
            @RequestBody ImportPreviewPayload payload,
            HttpServletRequest request) {
        return api.respond(request, () -> imports.preview(payload.fileName(), payload.content()));
    }

    @GetMapping("/imports/{batchId}")
    public Result<ImportPreview> batch(@PathVariable String batchId, HttpServletRequest request) {
        return api.respond(request, () -> imports.batch(batchId));
    }

    @PostMapping("/imports/{batchId}/commit")
    public Result<ImportResult> commit(
            @PathVariable String batchId,
            @RequestBody CommitPayload payload,
            HttpServletRequest request) {
        return api.respond(request, () -> imports.commit(batchId, payload.idempotencyKey()));
    }

    public record ImportPreviewPayload(String fileName, String content) {}

    public record CommitPayload(String idempotencyKey) {}
}
