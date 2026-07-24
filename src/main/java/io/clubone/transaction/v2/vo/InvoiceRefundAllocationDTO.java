package io.clubone.transaction.v2.vo;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

public record InvoiceRefundAllocationDTO(
        UUID refundAllocationId,
        UUID clientPaymentRefundId,
        UUID invoiceId,
        BigDecimal allocatedAmount,
        OffsetDateTime createdOn
) {
}