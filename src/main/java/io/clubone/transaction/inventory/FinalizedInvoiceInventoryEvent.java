package io.clubone.transaction.inventory;

import java.util.List;
import java.util.UUID;

public record FinalizedInvoiceInventoryEvent(
        UUID invoiceId,
        UUID clientPaymentTransactionId,
        UUID actorId,
        UUID locationId,
        UUID applicationId,
        List<UUID> promotionApplicabilityIds,
        String correlationId
) {
    public FinalizedInvoiceInventoryEvent {
        promotionApplicabilityIds = promotionApplicabilityIds == null
                ? List.of()
                : List.copyOf(promotionApplicabilityIds);
    }
}
