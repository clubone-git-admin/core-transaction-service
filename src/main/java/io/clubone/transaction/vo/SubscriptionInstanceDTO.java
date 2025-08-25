package io.clubone.transaction.vo;

import java.time.LocalDate;
import java.util.UUID;
import lombok.Data;

@Data
public class SubscriptionInstanceDTO {
    private UUID subscriptionPlanId;
    private LocalDate startDate;
    private LocalDate nextBillingDate;
    private UUID subscriptionInstanceStatusId;
    private UUID createdBy;
    private UUID invoiceId;
}