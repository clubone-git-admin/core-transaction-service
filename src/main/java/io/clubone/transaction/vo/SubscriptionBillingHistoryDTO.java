package io.clubone.transaction.vo;

import java.math.BigDecimal;
import java.util.UUID;
import lombok.Data;

@Data
public class SubscriptionBillingHistoryDTO {
    private UUID subscriptionInstanceId;
    private BigDecimal amount;
    private UUID clientPaymentIntentId;
    private UUID clientPaymentTransactionId;
    private UUID subscriptionInstanceStatusId;
    private String failureReason;
}
