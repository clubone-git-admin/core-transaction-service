package io.clubone.transaction.service;

import java.util.List;
import java.util.UUID;

import io.clubone.transaction.request.SubscriptionPlanBatchCreateRequest;
import io.clubone.transaction.request.SubscriptionPlanCreateRequest;
import io.clubone.transaction.response.SubscriptionPlanBatchCreateResponse;
import io.clubone.transaction.response.SubscriptionPlanCreateResponse;
import io.clubone.transaction.v2.vo.InvoiceDetailDTO;
import io.clubone.transaction.v2.vo.SubscriptionPlanSummaryDTO;

public interface SubscriptionPlanService {
	 SubscriptionPlanCreateResponse createPlanWithChildren(SubscriptionPlanCreateRequest request, UUID createdBy);
	 SubscriptionPlanBatchCreateResponse createPlans(SubscriptionPlanBatchCreateRequest batchReq, UUID createdBy);
	 InvoiceDetailDTO getSubscriptionDetail(UUID subscriptionPlanId);
	 List<SubscriptionPlanSummaryDTO> getClientSubscriptionPlans(UUID clientRoleId);
	}

