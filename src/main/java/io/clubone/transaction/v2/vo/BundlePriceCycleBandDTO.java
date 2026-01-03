package io.clubone.transaction.v2.vo;

import java.math.BigDecimal;

public class BundlePriceCycleBandDTO {
    private BigDecimal unitPrice;
    private Integer downPaymentUnits;

    public BundlePriceCycleBandDTO() {}

    public BundlePriceCycleBandDTO(BigDecimal unitPrice, Integer downPaymentUnits) {
        this.unitPrice = unitPrice;
        this.downPaymentUnits = downPaymentUnits;
    }

    public BigDecimal getUnitPrice() {
        return unitPrice;
    }

    public void setUnitPrice(BigDecimal unitPrice) {
        this.unitPrice = unitPrice;
    }

    public Integer getDownPaymentUnits() {
        return downPaymentUnits;
    }

    public void setDownPaymentUnits(Integer downPaymentUnits) {
        this.downPaymentUnits = downPaymentUnits;
    }
   

	@Override
    public String toString() {
        return "BundlePriceCycleBandDTO{" +
                "unitPrice=" + unitPrice +
                ", downPaymentUnits=" + downPaymentUnits +
                '}';
    }
}

