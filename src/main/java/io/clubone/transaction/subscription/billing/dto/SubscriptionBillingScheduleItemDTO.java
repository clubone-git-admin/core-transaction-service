package io.clubone.transaction.subscription.billing.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

public class SubscriptionBillingScheduleItemDTO {

    private UUID billingScheduleId;
    private UUID clientAgreementId;
    private UUID subscriptionPlanId;
    private UUID subscriptionInstanceId;
    private Integer cycleNumber;
    private LocalDate billingPeriodStart;
    private LocalDate billingPeriodEnd;
    private LocalDate billingDate;
    private BigDecimal baseAmount; 
    private BigDecimal unitPrice;
    private BigDecimal overrideAmount;
    private BigDecimal systemAdjustmentAmount;
    private BigDecimal manualAdjustmentAmount;
    private BigDecimal discountAmount;
    private BigDecimal taxAmount;
    private BigDecimal taxPct;
    private BigDecimal finalAmount;
    private String statusCode;
    private String statusDisplayName;
    private Boolean isFreezeCycle;
    private Boolean isCancellationCycle;
    private Boolean isProrated;
    private Boolean isGenerated;
    private Boolean isLocked;
    private UUID invoiceId;
    private String notes;
    private int quantity;

    public UUID getBillingScheduleId() { return billingScheduleId; }
    public void setBillingScheduleId(UUID billingScheduleId) { this.billingScheduleId = billingScheduleId; }

    public UUID getClientAgreementId() { return clientAgreementId; }
    public void setClientAgreementId(UUID clientAgreementId) { this.clientAgreementId = clientAgreementId; }

    public UUID getSubscriptionPlanId() { return subscriptionPlanId; }
    public void setSubscriptionPlanId(UUID subscriptionPlanId) { this.subscriptionPlanId = subscriptionPlanId; }

    public UUID getSubscriptionInstanceId() { return subscriptionInstanceId; }
    public void setSubscriptionInstanceId(UUID subscriptionInstanceId) { this.subscriptionInstanceId = subscriptionInstanceId; }

    public Integer getCycleNumber() { return cycleNumber; }
    public void setCycleNumber(Integer cycleNumber) { this.cycleNumber = cycleNumber; }

    public LocalDate getBillingPeriodStart() { return billingPeriodStart; }
    public void setBillingPeriodStart(LocalDate billingPeriodStart) { this.billingPeriodStart = billingPeriodStart; }

    public LocalDate getBillingPeriodEnd() { return billingPeriodEnd; }
    public void setBillingPeriodEnd(LocalDate billingPeriodEnd) { this.billingPeriodEnd = billingPeriodEnd; }

    public LocalDate getBillingDate() { return billingDate; }
    public void setBillingDate(LocalDate billingDate) { this.billingDate = billingDate; }

    public BigDecimal getBaseAmount() { return baseAmount; }
    public void setBaseAmount(BigDecimal baseAmount) { this.baseAmount = baseAmount; }

    public BigDecimal getOverrideAmount() { return overrideAmount; }
    public void setOverrideAmount(BigDecimal overrideAmount) { this.overrideAmount = overrideAmount; }

    public BigDecimal getSystemAdjustmentAmount() { return systemAdjustmentAmount; }
    public void setSystemAdjustmentAmount(BigDecimal systemAdjustmentAmount) { this.systemAdjustmentAmount = systemAdjustmentAmount; }

    public BigDecimal getManualAdjustmentAmount() { return manualAdjustmentAmount; }
    public void setManualAdjustmentAmount(BigDecimal manualAdjustmentAmount) { this.manualAdjustmentAmount = manualAdjustmentAmount; }

    public BigDecimal getDiscountAmount() { return discountAmount; }
    public void setDiscountAmount(BigDecimal discountAmount) { this.discountAmount = discountAmount; }

    public BigDecimal getTaxAmount() { return taxAmount; }
    public void setTaxAmount(BigDecimal taxAmount) { this.taxAmount = taxAmount; }

    public BigDecimal getFinalAmount() { return finalAmount; }
    public void setFinalAmount(BigDecimal finalAmount) { this.finalAmount = finalAmount; }

    public String getStatusCode() { return statusCode; }
    public void setStatusCode(String statusCode) { this.statusCode = statusCode; }

    public String getStatusDisplayName() { return statusDisplayName; }
    public void setStatusDisplayName(String statusDisplayName) { this.statusDisplayName = statusDisplayName; }

    public Boolean getIsFreezeCycle() { return isFreezeCycle; }
    public void setIsFreezeCycle(Boolean isFreezeCycle) { this.isFreezeCycle = isFreezeCycle; }

    public Boolean getIsCancellationCycle() { return isCancellationCycle; }
    public void setIsCancellationCycle(Boolean isCancellationCycle) { this.isCancellationCycle = isCancellationCycle; }

    public Boolean getIsProrated() { return isProrated; }
    public void setIsProrated(Boolean isProrated) { this.isProrated = isProrated; }

    public Boolean getIsGenerated() { return isGenerated; }
    public void setIsGenerated(Boolean isGenerated) { this.isGenerated = isGenerated; }

    public Boolean getIsLocked() { return isLocked; }
    public void setIsLocked(Boolean isLocked) { this.isLocked = isLocked; }

    public UUID getInvoiceId() { return invoiceId; }
    public void setInvoiceId(UUID invoiceId) { this.invoiceId = invoiceId; }

    public String getNotes() { return notes; }
    public void setNotes(String notes) { this.notes = notes; }
	public BigDecimal getUnitPrice() {
		return unitPrice;
	}
	public void setUnitPrice(BigDecimal unitPrice) {
		this.unitPrice = unitPrice;
	}
	public BigDecimal getTaxPct() {
		return taxPct;
	}
	public void setTaxPct(BigDecimal taxPct) {
		this.taxPct = taxPct;
	}
	public int getQuantity() {
		return quantity;
	}
	public void setQuantity(int quantity) {
		this.quantity = quantity;
	}
	
    
}
