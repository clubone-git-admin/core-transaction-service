package io.clubone.transaction.subscription.billing.dto;

import java.math.BigDecimal;

public class UpdateBillingScheduleRequest {

    private BigDecimal overrideAmount;
    private String statusCode;
    private Boolean isFreezeCycle;
    private Boolean isCancellationCycle;
    private Boolean isProrated;
    private String notes;

    public BigDecimal getOverrideAmount() { return overrideAmount; }
    public void setOverrideAmount(BigDecimal overrideAmount) { this.overrideAmount = overrideAmount; }

    public String getStatusCode() { return statusCode; }
    public void setStatusCode(String statusCode) { this.statusCode = statusCode; }

    public Boolean getIsFreezeCycle() { return isFreezeCycle; }
    public void setIsFreezeCycle(Boolean isFreezeCycle) { this.isFreezeCycle = isFreezeCycle; }

    public Boolean getIsCancellationCycle() { return isCancellationCycle; }
    public void setIsCancellationCycle(Boolean isCancellationCycle) { this.isCancellationCycle = isCancellationCycle; }

    public Boolean getIsProrated() { return isProrated; }
    public void setIsProrated(Boolean isProrated) { this.isProrated = isProrated; }

    public String getNotes() { return notes; }
    public void setNotes(String notes) { this.notes = notes; }
}
