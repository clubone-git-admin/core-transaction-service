package io.clubone.transaction.v2.vo;

import java.math.BigDecimal;
import java.util.UUID;

public class InvoiceEntityPriceBandDTO {

    private UUID priceCycleBandId;
    private BigDecimal unitPrice;
    private Boolean isPriceOverridden;

    // --- Constructors ---
    public InvoiceEntityPriceBandDTO() {
    }

    public InvoiceEntityPriceBandDTO(UUID priceCycleBandId, BigDecimal unitPrice, Boolean isPriceOverridden) {
        this.priceCycleBandId = priceCycleBandId;
        this.unitPrice = unitPrice;
        this.isPriceOverridden = isPriceOverridden;
    }

    // --- Getters & Setters ---
    public UUID getPriceCycleBandId() {
        return priceCycleBandId;
    }

    public void setPriceCycleBandId(UUID priceCycleBandId) {
        this.priceCycleBandId = priceCycleBandId;
    }

    public BigDecimal getUnitPrice() {
        return unitPrice;
    }

    public void setUnitPrice(BigDecimal unitPrice) {
        this.unitPrice = unitPrice;
    }

    public Boolean getIsPriceOverridden() {
        return isPriceOverridden;
    }

    public void setIsPriceOverridden(Boolean isPriceOverridden) {
        this.isPriceOverridden = isPriceOverridden;
    }

    // --- toString() ---
    @Override
    public String toString() {
        return "InvoiceEntityPriceBandDTO{" +
                "priceCycleBandId=" + priceCycleBandId +
                ", unitPrice=" + unitPrice +
                ", isPriceOverridden=" + isPriceOverridden +
                '}';
    }
}
