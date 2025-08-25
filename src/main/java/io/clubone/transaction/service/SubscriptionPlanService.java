package io.clubone.transaction.service;

import java.util.UUID;

import io.clubone.transaction.request.SubscriptionPlanBatchCreateRequest;
import io.clubone.transaction.request.SubscriptionPlanCreateRequest;
import io.clubone.transaction.response.SubscriptionPlanBatchCreateResponse;
import io.clubone.transaction.response.SubscriptionPlanCreateResponse;

public interface SubscriptionPlanService {
	 SubscriptionPlanCreateResponse createPlanWithChildren(SubscriptionPlanCreateRequest request, UUID createdBy);
	 SubscriptionPlanBatchCreateResponse createPlans(SubscriptionPlanBatchCreateRequest batchReq, UUID createdBy);
	}

