package io.clubone.transaction.subscription.billing.service;

import io.clubone.transaction.subscription.billing.dto.*;

import java.util.UUID;

public interface SubscriptionBillingScheduleManageService {

    SubscriptionBillingScheduleListResponse getScheduleByClientAgreementId(UUID clientAgreementId);

    SimpleActionResponse updateScheduleRow(UUID billingScheduleId, UpdateBillingScheduleRequest request, UUID modifiedBy);

    SimpleActionResponse bulkUpdateScheduleRows(BulkUpdateBillingScheduleRequest request, UUID modifiedBy);

    SimpleActionResponse addAdjustment(UUID billingScheduleId, AddBillingScheduleAdjustmentRequest request, UUID createdBy);
    
    BillingScheduleAdjustmentListResponse getAdjustmentsByBillingScheduleId(UUID billingScheduleId);

    SimpleActionResponse updateAdjustment(UUID billingScheduleAdjustmentId,
                                          UpdateBillingScheduleAdjustmentRequest request,
                                          UUID modifiedBy,
                                          String userEmail,
                                          String ipAddress,
                                          String userAgent);

    SimpleActionResponse deactivateAdjustment(UUID billingScheduleAdjustmentId,
                                              UUID modifiedBy,
                                              String userEmail,
                                              String ipAddress,
                                              String userAgent);

    SimpleActionResponse regenerateFutureSchedule(RegenerateBillingScheduleRequest request,
                                                  UUID modifiedBy,
                                                  String userEmail,
                                                  String ipAddress,
                                                  String userAgent);

}
