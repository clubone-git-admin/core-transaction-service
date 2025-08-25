package io.clubone.transaction.response;

import java.time.OffsetDateTime;
import java.util.UUID;

public class SubscriptionBillingDayRuleDTO {

    private UUID subscriptionBillingDayRuleId;
    private UUID subscriptionFrequencyId;
    private String billingDay;
    private String displayName;
    private String description;
    private Boolean isActive;
    private OffsetDateTime createdOn;
    private UUID createdBy;
    private OffsetDateTime modifiedOn;
    private UUID modifiedBy;

    public UUID getSubscriptionBillingDayRuleId() {
        return subscriptionBillingDayRuleId;
    }

    public void setSubscriptionBillingDayRuleId(UUID subscriptionBillingDayRuleId) {
        this.subscriptionBillingDayRuleId = subscriptionBillingDayRuleId;
    }

    public UUID getSubscriptionFrequencyId() {
        return subscriptionFrequencyId;
    }

    public void setSubscriptionFrequencyId(UUID subscriptionFrequencyId) {
        this.subscriptionFrequencyId = subscriptionFrequencyId;
    }

    public String getBillingDay() {
        return billingDay;
    }

    public void setBillingDay(String billingDay) {
        this.billingDay = billingDay;
    }

    public String getDisplayName() {
        return displayName;
    }

    public void setDisplayName(String displayName) {
        this.displayName = displayName;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public Boolean getIsActive() {
        return isActive;
    }

    public void setIsActive(Boolean active) {
        isActive = active;
    }

    public OffsetDateTime getCreatedOn() {
        return createdOn;
    }

    public void setCreatedOn(OffsetDateTime createdOn) {
        this.createdOn = createdOn;
    }

    public UUID getCreatedBy() {
        return createdBy;
    }

    public void setCreatedBy(UUID createdBy) {
        this.createdBy = createdBy;
    }

    public OffsetDateTime getModifiedOn() {
        return modifiedOn;
    }

    public void setModifiedOn(OffsetDateTime modifiedOn) {
        this.modifiedOn = modifiedOn;
    }

    public UUID getModifiedBy() {
        return modifiedBy;
    }

    public void setModifiedBy(UUID modifiedBy) {
        this.modifiedBy = modifiedBy;
    }
}
