package io.clubone.transaction.subscription.billing.service;

import java.util.UUID;

import io.clubone.transaction.request.SubscriptionPlanCreateRequest;

public interface SubscriptionBillingScheduleService {

    void generateInitialSchedule(SubscriptionPlanCreateRequest request,
                                 UUID subscriptionPlanId,
                                 UUID subscriptionInstanceId,
                                 UUID createdBy);
}
