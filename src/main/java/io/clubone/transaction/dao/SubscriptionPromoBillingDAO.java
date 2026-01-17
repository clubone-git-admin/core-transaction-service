package io.clubone.transaction.dao;

import java.util.List;
import java.util.UUID;

import io.clubone.transaction.v2.vo.SubscriptionBillingPromotionDTO;
import io.clubone.transaction.v2.vo.SubscriptionPlanPromoDTO;

public interface SubscriptionPromoBillingDAO {

    /**
     * Insert promos for a subscription plan into client_subscription_billing.subscription_plan_promo.
     * Returns number of rows inserted.
     */
    int insertSubscriptionPlanPromos(UUID subscriptionPlanId, UUID actorId, List<SubscriptionPlanPromoDTO> promos);

    /**
     * Insert promos for a billing history record into client_subscription_billing.subscription_billing_promotion.
     * Returns number of rows inserted.
     */
    int insertSubscriptionBillingPromotions(UUID subscriptionBillingHistoryId, List<SubscriptionBillingPromotionDTO> promos);
}

