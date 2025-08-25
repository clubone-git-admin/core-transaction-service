package io.clubone.transaction.v2.vo;

import java.util.UUID;

public class PromoDTO {
	private UUID promotionId; // required
	private UUID promotionEffectId; // nullable
	private Integer cycleStart; // required
	private Integer cycleEnd; // nullable
	private UUID priceCycleBandId; // nullable
	private Boolean isActive = true; // enforced by table constraint

	public UUID getPromotionId() {
		return promotionId;
	}

	public void setPromotionId(UUID promotionId) {
		this.promotionId = promotionId;
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

	public void setIsActive(Boolean isActive) {
		this.isActive = isActive;
	}

}
