package com.payflow.api.dto.offline;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import java.util.List;

public record OfflineSyncRequest(
        @NotEmpty(message = "Receipts list cannot be empty")
        @Valid
        List<OfflineReceiptDto> receipts
) {}
