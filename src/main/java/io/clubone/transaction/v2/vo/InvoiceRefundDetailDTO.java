package io.clubone.transaction.v2.vo;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

public record InvoiceRefundDetailDTO(
        UUID clientPaymentRefundId,
        UUID clientPaymentTransactionId,
        UUID invoiceId,
        BigDecimal refundAmount,
        String currencyCode,
        String gatewayName,
        String methodTypeName,
        String refundStatusCode,
        String refundReasonCode,
        String comments,
        String failureReason,
        String idempotencyKey,
        String gatewayRefundId,
        boolean webhookReconciled,
        OffsetDateTime webhookReconciledOn,
        OffsetDateTime createdOn
) {
}