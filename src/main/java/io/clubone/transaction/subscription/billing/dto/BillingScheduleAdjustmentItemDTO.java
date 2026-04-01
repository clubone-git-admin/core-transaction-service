package io.clubone.transaction.subscription.billing.dto;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

public class BillingScheduleAdjustmentItemDTO {

    private UUID billingScheduleAdjustmentId;
    private UUID billingScheduleId;
    private String adjustmentTypeCode;
    private String adjustmentTypeDisplayName;
    private BigDecimal amount;
    private Boolean isSystemGenerated;
    private String referenceEntityType;
    private UUID referenceEntityId;
    private String notes;
    private Boolean isActive;
    private OffsetDateTime createdOn;
    private UUID createdBy;
    private OffsetDateTime modifiedOn;
    private UUID modifiedBy;

    public UUID getBillingScheduleAdjustmentId() { return billingScheduleAdjustmentId; }
    public void setBillingScheduleAdjustmentId(UUID billingScheduleAdjustmentId) { this.billingScheduleAdjustmentId = billingScheduleAdjustmentId; }

    public UUID getBillingScheduleId() { return billingScheduleId; }
    public void setBillingScheduleId(UUID billingScheduleId) { this.billingScheduleId = billingScheduleId; }

    public String getAdjustmentTypeCode() { return adjustmentTypeCode; }
    public void setAdjustmentTypeCode(String adjustmentTypeCode) { this.adjustmentTypeCode = adjustmentTypeCode; }

    public String getAdjustmentTypeDisplayName() { return adjustmentTypeDisplayName; }
    public void setAdjustmentTypeDisplayName(String adjustmentTypeDisplayName) { this.adjustmentTypeDisplayName = adjustmentTypeDisplayName; }

    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal amount) { this.amount = amount; }

    public Boolean getIsSystemGenerated() { return isSystemGenerated; }
    public void setIsSystemGenerated(Boolean isSystemGenerated) { this.isSystemGenerated = isSystemGenerated; }

    public String getReferenceEntityType() { return referenceEntityType; }
    public void setReferenceEntityType(String referenceEntityType) { this.referenceEntityType = referenceEntityType; }

    public UUID getReferenceEntityId() { return referenceEntityId; }
    public void setReferenceEntityId(UUID referenceEntityId) { this.referenceEntityId = referenceEntityId; }

    public String getNotes() { return notes; }
    public void setNotes(String notes) { this.notes = notes; }

    public Boolean getIsActive() { return isActive; }
    public void setIsActive(Boolean isActive) { this.isActive = isActive; }

    public OffsetDateTime getCreatedOn() { return createdOn; }
    public void setCreatedOn(OffsetDateTime createdOn) { this.createdOn = createdOn; }

    public UUID getCreatedBy() { return createdBy; }
    public void setCreatedBy(UUID createdBy) { this.createdBy = createdBy; }

    public OffsetDateTime getModifiedOn() { return modifiedOn; }
    public void setModifiedOn(OffsetDateTime modifiedOn) { this.modifiedOn = modifiedOn; }

    public UUID getModifiedBy() { return modifiedBy; }
    public void setModifiedBy(UUID modifiedBy) { this.modifiedBy = modifiedBy; }
}
