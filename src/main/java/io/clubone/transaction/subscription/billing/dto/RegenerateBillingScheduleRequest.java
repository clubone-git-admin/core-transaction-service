package io.clubone.transaction.subscription.billing.dto;

import java.time.LocalDate;
import java.util.UUID;

public class RegenerateBillingScheduleRequest {

    private UUID clientAgreementId;
    private LocalDate fromBillingDate;
    private Boolean preserveManualOverrides;
    private Integer horizonMonths;

    public UUID getClientAgreementId() { return clientAgreementId; }
    public void setClientAgreementId(UUID clientAgreementId) { this.clientAgreementId = clientAgreementId; }

    public LocalDate getFromBillingDate() { return fromBillingDate; }
    public void setFromBillingDate(LocalDate fromBillingDate) { this.fromBillingDate = fromBillingDate; }

    public Boolean getPreserveManualOverrides() { return preserveManualOverrides; }
    public void setPreserveManualOverrides(Boolean preserveManualOverrides) { this.preserveManualOverrides = preserveManualOverrides; }

    public Integer getHorizonMonths() { return horizonMonths; }
    public void setHorizonMonths(Integer horizonMonths) { this.horizonMonths = horizonMonths; }
    
}
