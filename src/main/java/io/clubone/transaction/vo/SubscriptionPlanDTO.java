package io.clubone.transaction.vo;

import java.math.BigDecimal;
import java.util.UUID;
import lombok.Data;

@Data
public class SubscriptionPlanDTO {
    private UUID entityId; // client_agreement_id
    private UUID clientPaymentMethodId;
    private BigDecimal amount;
    private UUID subscriptionFrequencyId;
    private Integer intervalCount;
    private UUID subscriptionBillingDayRuleId;
    private UUID entityTypeId;
    private UUID createdBy;
}
