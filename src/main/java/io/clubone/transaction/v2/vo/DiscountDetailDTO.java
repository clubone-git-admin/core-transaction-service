package io.clubone.transaction.v2.vo;

import java.math.BigDecimal;
import java.util.UUID;

public class DiscountDetailDTO {
	private UUID discountId;
	private BigDecimal discountRate; // % if Percentage Based (e.g., 10.00)
	private BigDecimal discountAmount; // absolute currency if Amount-based
	private CalculationMode calculationMode; // PERCENTAGE, AMOUNT_PER_QTY, AMOUNT_PER_LINE
    private UUID calculationTypeId;     // passthrough (helps when persisting rows)
    private UUID adjustmentTypeId;

	public UUID getDiscountId() {
		return discountId;
	}

	public void setDiscountId(UUID discountId) {
		this.discountId = discountId;
	}

	public BigDecimal getDiscountRate() {
		return discountRate;
	}

	public void setDiscountRate(BigDecimal discountRate) {
		this.discountRate = discountRate;
	}

	public BigDecimal getDiscountAmount() {
		return discountAmount;
	}

	public void setDiscountAmount(BigDecimal discountAmount) {
		this.discountAmount = discountAmount;
	}

	public CalculationMode getCalculationMode() {
		return calculationMode;
	}

	public void setCalculationMode(CalculationMode calculationMode) {
		this.calculationMode = calculationMode;
	}

	public UUID getCalculationTypeId() {
		return calculationTypeId;
	}

	public void setCalculationTypeId(UUID calculationTypeId) {
		this.calculationTypeId = calculationTypeId;
	}

	public UUID getAdjustmentTypeId() {
		return adjustmentTypeId;
	}

	public void setAdjustmentTypeId(UUID adjustmentTypeId) {
		this.adjustmentTypeId = adjustmentTypeId;
	}
	
	
}
