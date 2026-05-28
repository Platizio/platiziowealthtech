package com.platizio.wealthtech.dto;

import java.util.List;

public record BulkOrderUploadResponse(
        int totalRows,
        int validRows,
        int submittedRows,
        int failedRows,
        int batchSize,
        BulkOrderExecutionPlatform platform,
        boolean dryRun,
        List<BulkOrderUploadRowResult> rows
) {
}
