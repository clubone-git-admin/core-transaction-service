package io.clubone.transaction.response;

import java.time.LocalDate;
import java.util.UUID;
import lombok.Data;

@Data
public class SubscriptionPlanSummaryResponse {

	UUID subscriptionPlanId;
    LocalDate startDate;
    LocalDate endDate;
    String status;
    UUID entityTypeId;
    UUID entityId;
    UUID parentEntityId;
    UUID parentEntityTypeId;
    UUID levelId;
    String levelName;
    String parentEntityName;
}
