package io.clubone.transaction.subscription.billing.model;

import java.math.BigDecimal;
import java.util.UUID;

public class CyclePriceProjection {

    private UUID subscriptionPlanCyclePriceId;
    private UUID priceCycleBandId;
    private Integer cycleStart;
    private Integer cycleEnd;
    private BigDecimal effectiveUnitPrice;

    public UUID getSubscriptionPlanCyclePriceId() {
        return subscriptionPlanCyclePriceId;
    }

    public void setSubscriptionPlanCyclePriceId(UUID subscriptionPlanCyclePriceId) {
        this.subscriptionPlanCyclePriceId = subscriptionPlanCyclePriceId;
    }

    public UUID getPriceCycleBandId() {
        return priceCycleBandId;
    }

    public void setPriceCycleBandId(UUID priceCycleBandId) {
        this.priceCycleBandId = priceCycleBandId;
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

    public BigDecimal getEffectiveUnitPrice() {
        return effectiveUnitPrice;
    }

    public void setEffectiveUnitPrice(BigDecimal effectiveUnitPrice) {
        this.effectiveUnitPrice = effectiveUnitPrice;
    }
}
