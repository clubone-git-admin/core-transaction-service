package io.clubone.transaction.controller;

import jakarta.validation.Valid;

import java.util.List;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import io.clubone.transaction.helper.SubscriptionPlanHelper;
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
	
	@Autowired
	private SubscriptionPlanHelper helperService;

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
	
	 @GetMapping("/build-request")
	    public ResponseEntity<List<SubscriptionPlanCreateRequest>> buildRequest(
	            @RequestParam UUID invoiceId,
	            @RequestParam UUID transactionId) {
	        return ResponseEntity.ok(helperService.buildRequests(invoiceId, transactionId));
	    }
}
