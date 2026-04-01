package io.clubone.transaction.subscription.billing.dao;

import io.clubone.transaction.subscription.billing.dto.*;

import java.util.List;
import java.util.UUID;

public interface SubscriptionBillingScheduleManageDAO {

    List<SubscriptionBillingScheduleItemDTO> getScheduleByClientAgreementId(UUID clientAgreementId);

    UUID billingScheduleStatusId(String statusCode);

    UUID billingAdjustmentTypeId(String adjustmentTypeCode);

    int updateScheduleRow(UUID billingScheduleId, UUID statusId, UpdateBillingScheduleRequest request, UUID modifiedBy);

    int bulkUpdateScheduleRows(BulkUpdateBillingScheduleRequest request, UUID statusId, UUID modifiedBy);

    int insertAdjustment(UUID billingScheduleId, UUID adjustmentTypeId, AddBillingScheduleAdjustmentRequest request, UUID createdBy);

    int recomputeManualAdjustmentAmount(UUID billingScheduleId, UUID modifiedBy);

    boolean isEditableScheduleRow(UUID billingScheduleId);
    
    List<BillingScheduleAdjustmentItemDTO> getAdjustmentsByBillingScheduleId(UUID billingScheduleId);

    UUID findBillingScheduleIdByAdjustmentId(UUID billingScheduleAdjustmentId);

    int updateAdjustment(UUID billingScheduleAdjustmentId,
                         UpdateBillingScheduleAdjustmentRequest request,
                         UUID modifiedBy);

    int deactivateAdjustment(UUID billingScheduleAdjustmentId, UUID modifiedBy);

    int deleteFutureGeneratedRows(UUID clientAgreementId,
                                  java.time.LocalDate fromBillingDate,
                                  boolean preserveManualOverrides);

    List<SubscriptionBillingScheduleItemDTO> getEditableFutureRows(UUID clientAgreementId,
                                                                   java.time.LocalDate fromBillingDate);

    void insertAuditLog(String eventType,
                        String entityType,
                        UUID entityId,
                        String action,
                        UUID userId,
                        String userEmail,
                        String ipAddress,
                        String userAgent,
                        String detailsJson);

}
