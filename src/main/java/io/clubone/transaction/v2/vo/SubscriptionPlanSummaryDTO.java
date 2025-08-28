package io.clubone.transaction.v2.vo;

import java.time.LocalDate;
import java.util.UUID;

public record SubscriptionPlanSummaryDTO(
        UUID subscriptionPlanId,
        LocalDate startDate,
        LocalDate endDate,
        String status,
        UUID entityTypeId,
        UUID entityId,
        UUID parentEntityId,
        UUID parentEntityTypeId,
        UUID levelId
) {}

