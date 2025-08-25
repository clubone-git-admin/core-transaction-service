package io.clubone.transaction.v2.vo;


import java.math.BigDecimal;
import java.util.UUID;

public class CyclePriceDTO {
 private Integer cycleStart;           // >= 1
 private Integer cycleEnd;             // nullable
 private BigDecimal unitPrice;         // required
 private UUID priceCycleBandId;        // nullable
 private Boolean allowPosPriceOverride = false;
 // Optional window override
 private BigDecimal windowOverrideUnitPrice;
 private UUID windowOverriddenBy;
 private String windowOverrideNote;
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
public BigDecimal getUnitPrice() {
	return unitPrice;
}
public void setUnitPrice(BigDecimal unitPrice) {
	this.unitPrice = unitPrice;
}
public UUID getPriceCycleBandId() {
	return priceCycleBandId;
}
public void setPriceCycleBandId(UUID priceCycleBandId) {
	this.priceCycleBandId = priceCycleBandId;
}
public Boolean getAllowPosPriceOverride() {
	return allowPosPriceOverride;
}
public void setAllowPosPriceOverride(Boolean allowPosPriceOverride) {
	this.allowPosPriceOverride = allowPosPriceOverride;
}
public BigDecimal getWindowOverrideUnitPrice() {
	return windowOverrideUnitPrice;
}
public void setWindowOverrideUnitPrice(BigDecimal windowOverrideUnitPrice) {
	this.windowOverrideUnitPrice = windowOverrideUnitPrice;
}
public UUID getWindowOverriddenBy() {
	return windowOverriddenBy;
}
public void setWindowOverriddenBy(UUID windowOverriddenBy) {
	this.windowOverriddenBy = windowOverriddenBy;
}
public String getWindowOverrideNote() {
	return windowOverrideNote;
}
public void setWindowOverrideNote(String windowOverrideNote) {
	this.windowOverrideNote = windowOverrideNote;
}

 
}

