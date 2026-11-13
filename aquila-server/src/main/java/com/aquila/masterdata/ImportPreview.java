package com.aquila.masterdata;

import java.util.List;

public record ImportPreview(
        String batchId,
        String fileName,
        String status,
        int rowCount,
        int errorCount,
        List<ImportRow> rows) {}
