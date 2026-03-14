package io.clubone.transaction.subscription.billing.dao;


import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import io.clubone.transaction.subscription.billing.model.CyclePriceProjection;
import io.clubone.transaction.subscription.billing.model.SubscriptionBillingScheduleRow;

public interface SubscriptionBillingScheduleDAO {

    UUID billingScheduleStatusId(String statusCode);

    int[] batchInsertBillingSchedule(List<SubscriptionBillingScheduleRow> rows);

    boolean existsByInstanceAndCycle(UUID subscriptionInstanceId, Integer cycleNumber);

    Integer countFutureRows(UUID subscriptionInstanceId, LocalDate fromDate);

    List<CyclePriceProjection> findCyclePrices(UUID subscriptionPlanId);
}