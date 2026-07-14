package io.clubone.transaction.inventory;

import java.util.List;
import java.util.UUID;

public record InventoryProvisioningResult(
        UUID invoiceId,
        UUID clientRoleId,
        int invoiceEntityCount,
        int entitlementCount,
        int createdCount,
        int skippedCount,
        List<ProvisionedInventoryItem> items
) {
}
