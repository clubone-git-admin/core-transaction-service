package io.clubone.transaction.v2.vo;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

public record InvoiceTransactionDetailDTO(
        UUID transactionId,
        String transactionNumber,
        UUID clientPaymentTransactionId,
        String clientPaymentTransactionNumber,
        UUID clientPaymentIntentId,
        String paymentIntentStatus,
        String gatewayName,
        String gatewayPaymentId,
        String gatewayOrderId,
        String gatewayStatusCode,
        String methodTypeCode,
        String methodTypeName,
        String paymentTypeCode,
        String paymentTypeName,
        String cardLast4,
        String cardType,
        String cardNetwork,
        BigDecimal amount,
        String failureReason,
        OffsetDateTime createdOn
) {
}