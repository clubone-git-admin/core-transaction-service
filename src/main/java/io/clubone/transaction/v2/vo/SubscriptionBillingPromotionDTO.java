package io.clubone.transaction.v2.vo;

import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.util.UUID;

public class SubscriptionBillingPromotionDTO {

    @NotNull
    private UUID promotionVersionId;

    private UUID promotionEffectId;

    // numeric(12,3)
    private BigDecimal amountApplied;

    public UUID getPromotionVersionId() {
        return promotionVersionId;
    }

    public void setPromotionVersionId(UUID promotionVersionId) {
        this.promotionVersionId = promotionVersionId;
    }

    public UUID getPromotionEffectId() {
        return promotionEffectId;
    }

    public void setPromotionEffectId(UUID promotionEffectId) {
        this.promotionEffectId = promotionEffectId;
    }

    public BigDecimal getAmountApplied() {
        return amountApplied;
    }

    public void setAmountApplied(BigDecimal amountApplied) {
        this.amountApplied = amountApplied;
    }
}
