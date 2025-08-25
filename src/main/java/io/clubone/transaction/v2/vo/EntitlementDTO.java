package io.clubone.transaction.v2.vo;

import java.util.UUID;

public class EntitlementDTO {
	private UUID entitlementModeId; // required
	private Integer quantityPerCycle; // XOR totalEntitlement unless isUnlimited
	private Integer totalEntitlement;
	private Boolean isUnlimited = false;
	private Integer maxRedemptionsPerDay;

	public UUID getEntitlementModeId() {
		return entitlementModeId;
	}

	public void setEntitlementModeId(UUID entitlementModeId) {
		this.entitlementModeId = entitlementModeId;
	}

	public Integer getQuantityPerCycle() {
		return quantityPerCycle;
	}

	public void setQuantityPerCycle(Integer quantityPerCycle) {
		this.quantityPerCycle = quantityPerCycle;
	}

	public Integer getTotalEntitlement() {
		return totalEntitlement;
	}

	public void setTotalEntitlement(Integer totalEntitlement) {
		this.totalEntitlement = totalEntitlement;
	}

	public Boolean getIsUnlimited() {
		return isUnlimited;
	}

	public void setIsUnlimited(Boolean isUnlimited) {
		this.isUnlimited = isUnlimited;
	}

	public Integer getMaxRedemptionsPerDay() {
		return maxRedemptionsPerDay;
	}

	public void setMaxRedemptionsPerDay(Integer maxRedemptionsPerDay) {
		this.maxRedemptionsPerDay = maxRedemptionsPerDay;
	}

}
