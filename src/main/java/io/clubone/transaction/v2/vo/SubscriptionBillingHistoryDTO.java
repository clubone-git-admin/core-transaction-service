package io.clubone.transaction.v2.vo;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

public class SubscriptionBillingHistoryDTO {

    private UUID subscriptionInstanceId;

    // If null → DB default now()
    private OffsetDateTime billingAttemptOn;

    private UUID invoiceId;
    private LocalDate paymentDueDate;
    private Integer cycleNumber;

    // monetary fields
    private BigDecimal amountNetExclTax;
    private BigDecimal amountTaxTotal;
    private BigDecimal amountDiscountTotalExclTax;

    // generated or optional
    private UUID priceCycleBandId;

    private Boolean posOverrideApplied = false;
    private String overrideNote;
    private UUID overriddenBy;

    private UUID prorationStrategyId;

    // int8 in DB
    private Long amountChargedMinor;

    public UUID getSubscriptionInstanceId() {
        return subscriptionInstanceId;
    }

    public void setSubscriptionInstanceId(UUID subscriptionInstanceId) {
        this.subscriptionInstanceId = subscriptionInstanceId;
    }

    public OffsetDateTime getBillingAttemptOn() {
        return billingAttemptOn;
    }

    public void setBillingAttemptOn(OffsetDateTime billingAttemptOn) {
        this.billingAttemptOn = billingAttemptOn;
    }

    public UUID getInvoiceId() {
        return invoiceId;
    }

    public void setInvoiceId(UUID invoiceId) {
        this.invoiceId = invoiceId;
    }

    public LocalDate getPaymentDueDate() {
        return paymentDueDate;
    }

    public void setPaymentDueDate(LocalDate paymentDueDate) {
        this.paymentDueDate = paymentDueDate;
    }

    public Integer getCycleNumber() {
        return cycleNumber;
    }

    public void setCycleNumber(Integer cycleNumber) {
        this.cycleNumber = cycleNumber;
    }

    public BigDecimal getAmountNetExclTax() {
        return amountNetExclTax;
    }

    public void setAmountNetExclTax(BigDecimal amountNetExclTax) {
        this.amountNetExclTax = amountNetExclTax;
    }

    public BigDecimal getAmountTaxTotal() {
        return amountTaxTotal;
    }

    public void setAmountTaxTotal(BigDecimal amountTaxTotal) {
        this.amountTaxTotal = amountTaxTotal;
    }

    public BigDecimal getAmountDiscountTotalExclTax() {
        return amountDiscountTotalExclTax;
    }

    public void setAmountDiscountTotalExclTax(BigDecimal amountDiscountTotalExclTax) {
        this.amountDiscountTotalExclTax = amountDiscountTotalExclTax;
    }

    public UUID getPriceCycleBandId() {
        return priceCycleBandId;
    }

    public void setPriceCycleBandId(UUID priceCycleBandId) {
        this.priceCycleBandId = priceCycleBandId;
    }

    public Boolean getPosOverrideApplied() {
        return posOverrideApplied;
    }

    public void setPosOverrideApplied(Boolean posOverrideApplied) {
        this.posOverrideApplied = posOverrideApplied;
    }

    public String getOverrideNote() {
        return overrideNote;
    }

    public void setOverrideNote(String overrideNote) {
        this.overrideNote = overrideNote;
    }

    public UUID getOverriddenBy() {
        return overriddenBy;
    }

    public void setOverriddenBy(UUID overriddenBy) {
        this.overriddenBy = overriddenBy;
    }

    public UUID getProrationStrategyId() {
        return prorationStrategyId;
    }

    public void setProrationStrategyId(UUID prorationStrategyId) {
        this.prorationStrategyId = prorationStrategyId;
    }

    public Long getAmountChargedMinor() {
        return amountChargedMinor;
    }

    public void setAmountChargedMinor(Long amountChargedMinor) {
        this.amountChargedMinor = amountChargedMinor;
    }
}

