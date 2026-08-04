package io.clubone.transaction.inventory;

import java.util.UUID;

public record FinalizedInvoiceInventoryEvent(
        UUID invoiceId,
        UUID clientPaymentTransactionId,
        UUID actorId,
        UUID locationId,
        UUID applicationId,
        String correlationId
) {
}