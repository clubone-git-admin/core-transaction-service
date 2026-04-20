package io.clubone.transaction.subscription.billing.service.impl;

import io.clubone.transaction.subscription.billing.dao.SubscriptionBillingScheduleManageDAO;
import io.clubone.transaction.subscription.billing.dto.*;
import io.clubone.transaction.subscription.billing.service.SubscriptionBillingScheduleManageService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
public class SubscriptionBillingScheduleManageServiceImpl implements SubscriptionBillingScheduleManageService {

	private final SubscriptionBillingScheduleManageDAO scheduleDAO;

	private final com.fasterxml.jackson.databind.ObjectMapper objectMapper = new com.fasterxml.jackson.databind.ObjectMapper();

	public SubscriptionBillingScheduleManageServiceImpl(SubscriptionBillingScheduleManageDAO scheduleDAO) {
		this.scheduleDAO = scheduleDAO;
	}

	@Override
	public SubscriptionBillingScheduleListResponse getScheduleByClientAgreementId(UUID clientAgreementId) {
		List<SubscriptionBillingScheduleItemDTO> rows = scheduleDAO.getScheduleByClientAgreementId(clientAgreementId);

		SubscriptionBillingScheduleListResponse resp = new SubscriptionBillingScheduleListResponse();
		resp.setClientAgreementId(clientAgreementId);
		resp.setRows(rows);
		resp.setTotalRows(rows.size());
		return resp;
	}

	@Override
	@Transactional
	public SimpleActionResponse updateScheduleRow(UUID billingScheduleId, UpdateBillingScheduleRequest request,
			UUID modifiedBy) {
		if (!scheduleDAO.isEditableScheduleRow(billingScheduleId)) {
			throw new IllegalStateException("Schedule row is not editable. It may be invoiced or locked.");
		}

		if (request.getStatusCode() == null || request.getStatusCode().isBlank()) {
			throw new IllegalArgumentException("statusCode is required");
		}

		UUID statusId = scheduleDAO.billingScheduleStatusId(request.getStatusCode());
		int updated = scheduleDAO.updateScheduleRow(billingScheduleId, statusId, request, modifiedBy);

		SimpleActionResponse resp = new SimpleActionResponse();
		resp.setSuccess(updated > 0);
		resp.setAffectedCount(updated);
		resp.setMessage(updated > 0 ? "Schedule row updated successfully" : "No schedule row updated");
		return resp;
	}

	@Override
	@Transactional
	public SimpleActionResponse bulkUpdateScheduleRows(BulkUpdateBillingScheduleRequest request, UUID modifiedBy) {
		if (request.getClientAgreementId() == null) {
			throw new IllegalArgumentException("clientAgreementId is required");
		}
		if (request.getStatusCode() == null || request.getStatusCode().isBlank()) {
			throw new IllegalArgumentException("statusCode is required");
		}

		UUID statusId = scheduleDAO.billingScheduleStatusId(request.getStatusCode());
		int updated = scheduleDAO.bulkUpdateScheduleRows(request, statusId, modifiedBy);

		SimpleActionResponse resp = new SimpleActionResponse();
		resp.setSuccess(updated > 0);
		resp.setAffectedCount(updated);
		resp.setMessage(
				updated > 0 ? "Bulk schedule update completed successfully" : "No schedule rows matched for update");
		return resp;
	}

	@Override
	@Transactional
	public SimpleActionResponse addAdjustment(UUID billingScheduleId,
	        AddBillingScheduleAdjustmentRequest request,
	        UUID createdBy) {

	    System.out.println("===== START addAdjustment =====");
	    System.out.println("billingScheduleId: " + billingScheduleId);
	    System.out.println("createdBy: " + createdBy);

	    if (request != null) {
	        System.out.println("Request.adjustmentTypeCode: " + request.getAdjustmentTypeCode());
	        System.out.println("Request.amount: " + request.getAmount());
	        System.out.println("Request.reason: " + request.getReason());
	        System.out.println("Request.referenceEntityType: " + request.getReferenceEntityType());
	        System.out.println("Request.referenceEntityId: " + request.getReferenceEntityId());
	    } else {
	        System.out.println("Request is NULL");
	    }

	    // 🔍 Check editable
	    boolean isEditable = scheduleDAO.isEditableScheduleRow(billingScheduleId);
	    System.out.println("isEditableScheduleRow: " + isEditable);

	    if (!isEditable) {
	        System.out.println("❌ Schedule row is NOT editable");
	        throw new IllegalStateException("Adjustment cannot be added. Schedule row is invoiced or locked.");
	    }

	    // 🔍 Validation
	    if (request.getAdjustmentTypeCode() == null || request.getAdjustmentTypeCode().isBlank()) {
	        System.out.println("❌ adjustmentTypeCode is missing");
	        throw new IllegalArgumentException("adjustmentTypeCode is required");
	    }

	    if (request.getAmount() == null || request.getAmount().signum() == 0) {
	        System.out.println("❌ amount is invalid: " + request.getAmount());
	        throw new IllegalArgumentException("amount must be non-zero");
	    }

	    // 🔍 Fetch adjustment type ID
	    UUID adjustmentTypeId = scheduleDAO.billingAdjustmentTypeId(request.getAdjustmentTypeCode());
	    System.out.println("Fetched adjustmentTypeId: " + adjustmentTypeId);

	    if (adjustmentTypeId == null) {
	        System.out.println("❌ No adjustmentTypeId found for code: " + request.getAdjustmentTypeCode());
	        throw new IllegalArgumentException("Invalid adjustmentTypeCode: " + request.getAdjustmentTypeCode());
	    }

	    // 🔍 Insert adjustment
	    System.out.println("Inserting adjustment...");
	    int inserted = scheduleDAO.insertAdjustment(
	            billingScheduleId,
	            adjustmentTypeId,
	            request,
	            createdBy
	    );
	    System.out.println("Rows inserted: " + inserted);

	    // Optional recompute
	    // System.out.println("Recomputing manual adjustment amount...");
	    // scheduleDAO.recomputeManualAdjustmentAmount(billingScheduleId, createdBy);

	    // 🔍 Response
	    SimpleActionResponse resp = new SimpleActionResponse();
	    resp.setSuccess(inserted > 0);
	    resp.setAffectedCount(inserted);
	    resp.setMessage(inserted > 0 ? "Adjustment added successfully" : "Adjustment insert failed");

	    System.out.println("Response.success: " + resp.isSuccess());
	    System.out.println("Response.affectedCount: " + resp.getAffectedCount());
	    System.out.println("Response.message: " + resp.getMessage());

	    System.out.println("===== END addAdjustment =====");

	    return resp;
	}
	@Override
	public BillingScheduleAdjustmentListResponse getAdjustmentsByBillingScheduleId(UUID billingScheduleId) {
		List<BillingScheduleAdjustmentItemDTO> rows = scheduleDAO.getAdjustmentsByBillingScheduleId(billingScheduleId);

		BillingScheduleAdjustmentListResponse resp = new BillingScheduleAdjustmentListResponse();
		resp.setBillingScheduleId(billingScheduleId);
		resp.setAdjustments(rows);
		resp.setTotalRows(rows.size());
		return resp;
	}

	@Override
	@Transactional
	public SimpleActionResponse updateAdjustment(UUID billingScheduleAdjustmentId,
			UpdateBillingScheduleAdjustmentRequest request, UUID modifiedBy, String userEmail, String ipAddress,
			String userAgent) {
		if (request.getAmount() == null || request.getAmount().signum() == 0) {
			throw new IllegalArgumentException("amount must be non-zero");
		}

		UUID billingScheduleId = scheduleDAO.findBillingScheduleIdByAdjustmentId(billingScheduleAdjustmentId);
		if (!scheduleDAO.isEditableScheduleRow(billingScheduleId)) {
			throw new IllegalStateException("Parent schedule row is not editable.");
		}

		int updated = scheduleDAO.updateAdjustment(billingScheduleAdjustmentId, request, modifiedBy);
		//scheduleDAO.recomputeManualAdjustmentAmount(billingScheduleId, modifiedBy);

		writeAudit("BILLING_SCHEDULE", "SCHEDULE_ADJUSTMENT", billingScheduleAdjustmentId,
				"SCHEDULE_ADJUSTMENT_UPDATED", modifiedBy, userEmail, ipAddress, userAgent, request);

		SimpleActionResponse resp = new SimpleActionResponse();
		resp.setSuccess(updated > 0);
		resp.setAffectedCount(updated);
		resp.setMessage(updated > 0 ? "Adjustment updated successfully" : "No adjustment updated");
		return resp;
	}

	@Override
	@Transactional
	public SimpleActionResponse deactivateAdjustment(UUID billingScheduleAdjustmentId, UUID modifiedBy,
			String userEmail, String ipAddress, String userAgent) {
		UUID billingScheduleId = scheduleDAO.findBillingScheduleIdByAdjustmentId(billingScheduleAdjustmentId);
		if (!scheduleDAO.isEditableScheduleRow(billingScheduleId)) {
			throw new IllegalStateException("Parent schedule row is not editable.");
		}

		int updated = scheduleDAO.deactivateAdjustment(billingScheduleAdjustmentId, modifiedBy);
		//scheduleDAO.recomputeManualAdjustmentAmount(billingScheduleId, modifiedBy);

		writeAudit("BILLING_SCHEDULE", "SCHEDULE_ADJUSTMENT", billingScheduleAdjustmentId,
				"SCHEDULE_ADJUSTMENT_DEACTIVATED", modifiedBy, userEmail, ipAddress, userAgent, null);

		SimpleActionResponse resp = new SimpleActionResponse();
		resp.setSuccess(updated > 0);
		resp.setAffectedCount(updated);
		resp.setMessage(updated > 0 ? "Adjustment deactivated successfully" : "No adjustment deactivated");
		return resp;
	}

	private void writeAudit(String eventType, String entityType, UUID entityId, String action, UUID userId,
			String userEmail, String ipAddress, String userAgent, Object detailsObject) {
		try {
			String detailsJson = detailsObject == null ? null : objectMapper.writeValueAsString(detailsObject);
			scheduleDAO.insertAuditLog(eventType, entityType, entityId, action, userId, userEmail, ipAddress, userAgent,
					detailsJson);
		} catch (Exception ex) {
			throw new RuntimeException("Failed to write audit log", ex);
		}
	}

	@Override
	@Transactional
	public SimpleActionResponse regenerateFutureSchedule(RegenerateBillingScheduleRequest request, UUID modifiedBy,
			String userEmail, String ipAddress, String userAgent) {
		if (request.getClientAgreementId() == null) {
			throw new IllegalArgumentException("clientAgreementId is required");
		}

		java.time.LocalDate fromDate = request.getFromBillingDate() == null ? java.time.LocalDate.now()
				: request.getFromBillingDate();

		boolean preserveManualOverrides = Boolean.TRUE.equals(request.getPreserveManualOverrides());

		int deleted = scheduleDAO.deleteFutureGeneratedRows(request.getClientAgreementId(), fromDate,
				preserveManualOverrides);

		// Here you should implement a proper dedicated generation method:
		// generationService.regenerateFutureSchedule(request.getClientAgreementId(),
		// fromDate, request.getHorizonMonths(), modifiedBy);

		writeAudit("BILLING_SCHEDULE", "SCHEDULE", request.getClientAgreementId(), "SCHEDULE_REGENERATED", modifiedBy,
				userEmail, ipAddress, userAgent, request);

		SimpleActionResponse resp = new SimpleActionResponse();
		resp.setSuccess(true);
		resp.setAffectedCount(deleted);
		resp.setMessage("Future schedule regeneration initiated successfully. Deleted rows = " + deleted);
		return resp;
	}

}
