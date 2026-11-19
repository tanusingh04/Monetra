package com.payflow.api.dto.offline;

import java.util.UUID;

public record OfflineSyncItemResult(
        UUID txId,
        String status,
        UUID paymentId,
        String message
) {}
