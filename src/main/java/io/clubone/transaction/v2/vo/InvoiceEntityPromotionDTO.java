package io.clubone.transaction.v2.vo;

import java.math.BigDecimal;
import java.util.UUID;

public class InvoiceEntityPromotionDTO {
    private UUID promotionVersionId;
    private UUID promotionApplicabilityId;
    private UUID promotionEffectId;
    private BigDecimal promotionAmount;
	public UUID getPromotionVersionId() {
		return promotionVersionId;
	}
	public void setPromotionVersionId(UUID promotionVersionId) {
		this.promotionVersionId = promotionVersionId;
	}
	public UUID getPromotionApplicabilityId() {
		return promotionApplicabilityId;
	}
	public void setPromotionApplicabilityId(UUID promotionApplicabilityId) {
		this.promotionApplicabilityId = promotionApplicabilityId;
	}
	public UUID getPromotionEffectId() {
		return promotionEffectId;
	}
	public void setPromotionEffectId(UUID promotionEffectId) {
		this.promotionEffectId = promotionEffectId;
	}
	public BigDecimal getPromotionAmount() {
		return promotionAmount;
	}
	public void setPromotionAmount(BigDecimal promotionAmount) {
		this.promotionAmount = promotionAmount;
	}

   
}
