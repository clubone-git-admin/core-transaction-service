package io.clubone.transaction.v2.vo;

import java.util.UUID;

public class DiscountCodeDTO {
 private UUID discountId;
 private Integer cycleStart = 1;
 private Integer cycleEnd;         // nullable
 private UUID priceCycleBandId;    // nullable
 private Integer stackRank = 100;
 private Boolean isActive = true;
public UUID getDiscountId() {
	return discountId;
}
public void setDiscountId(UUID discountId) {
	this.discountId = discountId;
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
public Integer getStackRank() {
	return stackRank;
}
public void setStackRank(Integer stackRank) {
	this.stackRank = stackRank;
}
public Boolean getIsActive() {
	return isActive;
}
public void setIsActive(Boolean isActive) {
	this.isActive = isActive;
}

}
