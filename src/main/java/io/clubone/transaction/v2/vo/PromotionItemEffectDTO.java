package io.clubone.transaction.v2.vo;

import java.math.BigDecimal;
import java.util.UUID;

public class PromotionItemEffectDTO {

    private UUID promotionId;
    private UUID promotionVersionId;
    private UUID promotionApplicabilityId;

    private UUID itemId;

    private UUID promotionEffectId;
    private UUID effectTypeId;

    // You asked for description: lu_effect_type.name
    private String effectTypeDescription;

    private BigDecimal valueAmount;
    private BigDecimal valuePercent;

    public UUID getPromotionId() { return promotionId; }
    public void setPromotionId(UUID promotionId) { this.promotionId = promotionId; }

    public UUID getPromotionVersionId() { return promotionVersionId; }
    public void setPromotionVersionId(UUID promotionVersionId) { this.promotionVersionId = promotionVersionId; }

    public UUID getItemId() { return itemId; }
    public void setItemId(UUID itemId) { this.itemId = itemId; }

    public UUID getPromotionEffectId() { return promotionEffectId; }
    public void setPromotionEffectId(UUID promotionEffectId) { this.promotionEffectId = promotionEffectId; }

    public UUID getEffectTypeId() { return effectTypeId; }
    public void setEffectTypeId(UUID effectTypeId) { this.effectTypeId = effectTypeId; }

    public String getEffectTypeDescription() { return effectTypeDescription; }
    public void setEffectTypeDescription(String effectTypeDescription) { this.effectTypeDescription = effectTypeDescription; }

    public BigDecimal getValueAmount() { return valueAmount; }
    public void setValueAmount(BigDecimal valueAmount) { this.valueAmount = valueAmount; }

    public BigDecimal getValuePercent() { return valuePercent; }
    public void setValuePercent(BigDecimal valuePercent) { this.valuePercent = valuePercent; }
	public UUID getPromotionApplicabilityId() {
		return promotionApplicabilityId;
	}
	public void setPromotionApplicabilityId(UUID promotionApplicabilityId) {
		this.promotionApplicabilityId = promotionApplicabilityId;
	}
    
}

