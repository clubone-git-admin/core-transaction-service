package io.clubone.transaction.subscription.billing.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

public class BulkUpdateBillingScheduleRequest {

    private UUID clientAgreementId;
    private LocalDate fromBillingDate;
    private LocalDate toBillingDate;
    private Integer fromCycleNumber;
    private Integer toCycleNumber;
    private BigDecimal overrideAmount;
    private String statusCode;
    private Boolean isFreezeCycle;
    private Boolean isCancellationCycle;
    private Boolean isProrated;
    private String notes;

    public UUID getClientAgreementId() { return clientAgreementId; }
    public void setClientAgreementId(UUID clientAgreementId) { this.clientAgreementId = clientAgreementId; }

    public LocalDate getFromBillingDate() { return fromBillingDate; }
    public void setFromBillingDate(LocalDate fromBillingDate) { this.fromBillingDate = fromBillingDate; }

    public LocalDate getToBillingDate() { return toBillingDate; }
    public void setToBillingDate(LocalDate toBillingDate) { this.toBillingDate = toBillingDate; }

    public Integer getFromCycleNumber() { return fromCycleNumber; }
    public void setFromCycleNumber(Integer fromCycleNumber) { this.fromCycleNumber = fromCycleNumber; }

    public Integer getToCycleNumber() { return toCycleNumber; }
    public void setToCycleNumber(Integer toCycleNumber) { this.toCycleNumber = toCycleNumber; }

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
