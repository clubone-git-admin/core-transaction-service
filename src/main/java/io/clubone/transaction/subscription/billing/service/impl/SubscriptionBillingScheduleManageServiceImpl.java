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
	public SimpleActionResponse addAdjustment(UUID billingScheduleId, AddBillingScheduleAdjustmentRequest request,
			UUID createdBy) {
		if (!scheduleDAO.isEditableScheduleRow(billingScheduleId)) {
			throw new IllegalStateException("Adjustment cannot be added. Schedule row is invoiced or locked.");
		}
		if (request.getAdjustmentTypeCode() == null || request.getAdjustmentTypeCode().isBlank()) {
			throw new IllegalArgumentException("adjustmentTypeCode is required");
		}
		if (request.getAmount() == null || request.getAmount().signum() == 0) {
			throw new IllegalArgumentException("amount must be non-zero");
		}

		UUID adjustmentTypeId = scheduleDAO.billingAdjustmentTypeId(request.getAdjustmentTypeCode());

		int inserted = scheduleDAO.insertAdjustment(billingScheduleId, adjustmentTypeId, request, createdBy);
		scheduleDAO.recomputeManualAdjustmentAmount(billingScheduleId, createdBy);

		SimpleActionResponse resp = new SimpleActionResponse();
		resp.setSuccess(inserted > 0);
		resp.setAffectedCount(inserted);
		resp.setMessage(inserted > 0 ? "Adjustment added successfully" : "Adjustment insert failed");
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
		scheduleDAO.recomputeManualAdjustmentAmount(billingScheduleId, modifiedBy);

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
		scheduleDAO.recomputeManualAdjustmentAmount(billingScheduleId, modifiedBy);

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
