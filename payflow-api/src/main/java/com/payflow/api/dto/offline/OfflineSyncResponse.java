package com.payflow.api.dto.offline;

import java.util.List;

public record OfflineSyncResponse(
        int totalSubmitted,
        int totalSettled,
        int totalRejected,
        List<OfflineSyncItemResult> results
) {}
