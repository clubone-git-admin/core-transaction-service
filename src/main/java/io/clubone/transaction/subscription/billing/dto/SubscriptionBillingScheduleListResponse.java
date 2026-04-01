package io.clubone.transaction.subscription.billing.dto;

import java.util.List;
import java.util.UUID;

public class SubscriptionBillingScheduleListResponse {

    private UUID clientAgreementId;
    private Integer totalRows;
    private List<SubscriptionBillingScheduleItemDTO> rows;

    public UUID getClientAgreementId() { return clientAgreementId; }
    public void setClientAgreementId(UUID clientAgreementId) { this.clientAgreementId = clientAgreementId; }

    public Integer getTotalRows() { return totalRows; }
    public void setTotalRows(Integer totalRows) { this.totalRows = totalRows; }

    public List<SubscriptionBillingScheduleItemDTO> getRows() { return rows; }
    public void setRows(List<SubscriptionBillingScheduleItemDTO> rows) { this.rows = rows; }
}
