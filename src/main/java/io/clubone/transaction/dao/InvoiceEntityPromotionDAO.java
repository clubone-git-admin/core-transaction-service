package io.clubone.transaction.dao;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import io.clubone.transaction.v2.vo.SubscriptionBillingPromotionDTO;
import io.clubone.transaction.v2.vo.SubscriptionPlanPromoDTO;

public interface InvoiceEntityPromotionDAO {

    /**
     * Returns all active invoice_entity promotions for the given invoice_entity_id.
     */
    List<InvoiceEntityPromotionRow> fetchActivePromotionsByInvoiceEntityId(UUID invoiceEntityId);

    /**
     * Batch version: invoice_entity_id -> list of promotion rows.
     */
    Map<UUID, List<InvoiceEntityPromotionRow>> fetchActivePromotionsByInvoiceEntityIds(List<UUID> invoiceEntityIds);

    /**
     * Helper conversion for subscription_plan_promo insert payload.
     * You decide cycleStart/cycleEnd outside (or pass as args).
     */
    List<SubscriptionPlanPromoDTO> toSubscriptionPlanPromos(
            List<InvoiceEntityPromotionRow> promoRows,
            int cycleStart,
            Integer cycleEnd,
            UUID priceCycleBandId // optional (can be null)
    );

    /**
     * Helper conversion for subscription_billing_promotion insert payload.
     */
    List<SubscriptionBillingPromotionDTO> toSubscriptionBillingPromotions(List<InvoiceEntityPromotionRow> promoRows);

    /**
     * Raw row for internal use.
     */
    record InvoiceEntityPromotionRow(
            UUID invoiceEntityPromotionId,
            UUID invoiceEntityId,
            UUID promotionVersionId,
            UUID promotionEffectId,
            UUID promotionApplicabilityId,
            java.math.BigDecimal promotionAmount
    ) {}
}

