package io.clubone.transaction.subscription.billing.dto;

import java.util.List;
import java.util.UUID;

public class BillingScheduleAdjustmentListResponse {

    private UUID billingScheduleId;
    private Integer totalRows;
    private List<BillingScheduleAdjustmentItemDTO> adjustments;

    public UUID getBillingScheduleId() { return billingScheduleId; }
    public void setBillingScheduleId(UUID billingScheduleId) { this.billingScheduleId = billingScheduleId; }

    public Integer getTotalRows() { return totalRows; }
    public void setTotalRows(Integer totalRows) { this.totalRows = totalRows; }

    public List<BillingScheduleAdjustmentItemDTO> getAdjustments() { return adjustments; }
    public void setAdjustments(List<BillingScheduleAdjustmentItemDTO> adjustments) { this.adjustments = adjustments; }
}
