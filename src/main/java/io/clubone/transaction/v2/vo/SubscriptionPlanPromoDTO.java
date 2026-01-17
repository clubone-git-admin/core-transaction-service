package io.clubone.transaction.v2.vo;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.util.UUID;

public class SubscriptionPlanPromoDTO {

    @NotNull
    private UUID promotionVersionId;

    private UUID promotionEffectId;

    @NotNull
    @Positive
    private Integer cycleStart;     // 1-based

    private Integer cycleEnd;       // inclusive, null = open-ended

    private UUID priceCycleBandId;

    private Boolean isActive = true;

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

    public Integer getCycleStart() {
        return cycleStart;
    }

    public void setCycleStart(Integer cycleStart) {
        this.cycleStart = cycleStart;
    }

    public Integer getCycleEnd() {
        return cycleEnd;
    }

    public void setCycleEnd(Integer cycleEnd) {
        this.cycleEnd = cycleEnd;
    }

    public UUID getPriceCycleBandId() {
        return priceCycleBandId;
    }

    public void setPriceCycleBandId(UUID priceCycleBandId) {
        this.priceCycleBandId = priceCycleBandId;
    }

    public Boolean getIsActive() {
        return isActive;
    }

    public void setIsActive(Boolean active) {
        isActive = active;
    }
}
