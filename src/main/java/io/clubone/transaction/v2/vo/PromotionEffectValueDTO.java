package io.clubone.transaction.v2.vo;

import java.math.BigDecimal;
import java.util.UUID;

public class PromotionEffectValueDTO {

    private UUID promotionEffectId;
    private UUID effectTypeId;

    // "description" requested -> using lu_effect_type.name
    private String effectTypeDescription;

    private BigDecimal valueAmount;
    private BigDecimal valuePercent;

    public PromotionEffectValueDTO() {}

    public PromotionEffectValueDTO(
            UUID promotionEffectId,
            UUID effectTypeId,
            String effectTypeDescription,
            BigDecimal valueAmount,
            BigDecimal valuePercent
    ) {
        this.promotionEffectId = promotionEffectId;
        this.effectTypeId = effectTypeId;
        this.effectTypeDescription = effectTypeDescription;
        this.valueAmount = valueAmount;
        this.valuePercent = valuePercent;
    }

    public UUID getPromotionEffectId() {
        return promotionEffectId;
    }

    public void setPromotionEffectId(UUID promotionEffectId) {
        this.promotionEffectId = promotionEffectId;
    }

    public UUID getEffectTypeId() {
        return effectTypeId;
    }

    public void setEffectTypeId(UUID effectTypeId) {
        this.effectTypeId = effectTypeId;
    }

    public String getEffectTypeDescription() {
        return effectTypeDescription;
    }

    public void setEffectTypeDescription(String effectTypeDescription) {
        this.effectTypeDescription = effectTypeDescription;
    }

    public BigDecimal getValueAmount() {
        return valueAmount;
    }

    public void setValueAmount(BigDecimal valueAmount) {
        this.valueAmount = valueAmount;
    }

    public BigDecimal getValuePercent() {
        return valuePercent;
    }

    public void setValuePercent(BigDecimal valuePercent) {
        this.valuePercent = valuePercent;
    }
}
