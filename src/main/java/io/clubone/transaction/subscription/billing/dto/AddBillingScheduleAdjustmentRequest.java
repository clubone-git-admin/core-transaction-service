package io.clubone.transaction.subscription.billing.dto;

import java.math.BigDecimal;
import java.util.UUID;

public class AddBillingScheduleAdjustmentRequest {

    private String adjustmentTypeCode;
    private BigDecimal amount;
    private String reason;
    private String referenceEntityType;
    private UUID referenceEntityId;

    public String getAdjustmentTypeCode() { return adjustmentTypeCode; }
    public void setAdjustmentTypeCode(String adjustmentTypeCode) { this.adjustmentTypeCode = adjustmentTypeCode; }

    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal amount) { this.amount = amount; }

    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }

    public String getReferenceEntityType() { return referenceEntityType; }
    public void setReferenceEntityType(String referenceEntityType) { this.referenceEntityType = referenceEntityType; }

    public UUID getReferenceEntityId() { return referenceEntityId; }
    public void setReferenceEntityId(UUID referenceEntityId) { this.referenceEntityId = referenceEntityId; }
}
