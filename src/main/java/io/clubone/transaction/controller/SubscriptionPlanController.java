package io.clubone.transaction.controller;

import jakarta.validation.Valid;

import java.util.UUID;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import io.clubone.transaction.request.SubscriptionPlanBatchCreateRequest;
import io.clubone.transaction.request.SubscriptionPlanCreateRequest;
import io.clubone.transaction.response.SubscriptionPlanBatchCreateResponse;
import io.clubone.transaction.response.SubscriptionPlanCreateResponse;
import io.clubone.transaction.service.SubscriptionPlanService;

@RestController
@RequestMapping("/subscription/api/plan")
public class SubscriptionPlanController {

	@Autowired
	private SubscriptionPlanService service;

	@PostMapping
	public ResponseEntity<SubscriptionPlanCreateResponse> createPlan(
			@Valid @RequestBody SubscriptionPlanCreateRequest request,
			@RequestHeader(name = "X-User", required = false) String userHeader) {
		UUID createdBy = request.getCreatedBy() == null ? UUID.randomUUID() : request.getCreatedBy();
		SubscriptionPlanCreateResponse resp = service.createPlanWithChildren(request, createdBy);
		return ResponseEntity.ok(resp);
	}

	@PostMapping("/batch")
	public ResponseEntity<SubscriptionPlanBatchCreateResponse> createPlans(
			@Valid @RequestBody SubscriptionPlanBatchCreateRequest request,
			@RequestHeader(name = "X-User", required = false) String userHeader) {
		UUID createdBy = request.getCreatedBy() == null ? UUID.randomUUID() : request.getCreatedBy();
		return ResponseEntity.ok(service.createPlans(request, createdBy));
	}
}
