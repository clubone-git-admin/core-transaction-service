package io.clubone.transaction.v2.vo;

import java.math.BigDecimal;
import java.util.UUID;

public class InvoiceEntityPromotionDTO {
    private UUID invoiceEntityPromotionId;
    private UUID invoiceEntityId;
    private UUID promotionVersionId;
    private UUID promotionApplicabilityId;
    private UUID promotionEffectId;
    private BigDecimal promotionAmount;
    private String promotionName;

	public UUID getInvoiceEntityPromotionId() {
		return invoiceEntityPromotionId;
	}
	public void setInvoiceEntityPromotionId(UUID invoiceEntityPromotionId) {
		this.invoiceEntityPromotionId = invoiceEntityPromotionId;
	}
	public UUID getInvoiceEntityId() {
		return invoiceEntityId;
	}
	public void setInvoiceEntityId(UUID invoiceEntityId) {
		this.invoiceEntityId = invoiceEntityId;
	}
	public String getPromotionName() {
		return promotionName;
	}
	public void setPromotionName(String promotionName) {
		this.promotionName = promotionName;
	}
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
