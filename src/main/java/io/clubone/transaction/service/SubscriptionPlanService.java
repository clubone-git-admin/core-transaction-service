package io.clubone.transaction.service;

import java.util.List;
import java.util.UUID;

import io.clubone.transaction.request.ReplacePlanPaymentMethodRequest;
import io.clubone.transaction.request.SubscriptionPlanBatchCreateRequest;
import io.clubone.transaction.request.SubscriptionPlanCreateRequest;
import io.clubone.transaction.response.SubscriptionPlanBatchCreateResponse;
import io.clubone.transaction.response.SubscriptionPlanCreateResponse;
import io.clubone.transaction.subscription.billing.dto.SimpleActionResponse;
import io.clubone.transaction.v2.vo.InvoiceDetailDTO;
import io.clubone.transaction.v2.vo.SubscriptionPlanSummaryDTO;

public interface SubscriptionPlanService {
	 SubscriptionPlanCreateResponse createPlanWithChildren(SubscriptionPlanCreateRequest request, UUID createdBy);
	 SubscriptionPlanBatchCreateResponse createPlans(SubscriptionPlanBatchCreateRequest batchReq, UUID createdBy);
	 InvoiceDetailDTO getSubscriptionDetail(UUID subscriptionPlanId);
	 List<SubscriptionPlanSummaryDTO> getClientSubscriptionPlans(UUID clientRoleId);

	 /** Cancels the plan for billing: soft-deactivates plan and its 1:1 active mandate. */
	 SimpleActionResponse cancelPlan(UUID subscriptionPlanId, UUID modifiedBy);

	 /** Replaces card on this plan only and rebinds that plan's mandate. */
	 SimpleActionResponse replacePaymentMethod(UUID subscriptionPlanId, ReplacePlanPaymentMethodRequest request,
			 UUID modifiedBy);
	}

