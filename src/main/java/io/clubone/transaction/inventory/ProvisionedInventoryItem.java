package io.clubone.transaction.inventory;

import java.math.BigDecimal;
import java.util.UUID;

public record ProvisionedInventoryItem(
        UUID invoiceEntityId,
        UUID itemVersionId,
        UUID serviceMappingId,
        UUID redemptionRuleId,
        UUID clientInventoryItemId,
        BigDecimal quantity,
        String idempotencyKey
) {
}
