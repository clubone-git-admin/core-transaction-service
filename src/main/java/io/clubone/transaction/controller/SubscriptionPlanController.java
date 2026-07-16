package io.clubone.transaction.controller;

import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.clubone.transaction.helper.SubscriptionPlanHelper;
import io.clubone.transaction.request.BillingQuoteFinalizeSpec;
import io.clubone.transaction.request.SubscriptionPlanBatchCreateRequest;
import io.clubone.transaction.request.SubscriptionPlanCreateRequest;
import io.clubone.transaction.response.BillingQuoteLineItemsResponse;
import io.clubone.transaction.response.SubscriptionPlanBatchCreateResponse;
import io.clubone.transaction.response.SubscriptionPlanCreateResponse;
import io.clubone.transaction.security.AccessContext;
import io.clubone.transaction.service.SubscriptionPlanService;
import io.clubone.transaction.v2.vo.InvoiceDetailDTO;
import io.clubone.transaction.v2.vo.SubscriptionPlanSummaryDTO;
import jakarta.validation.Valid;

@RestController
@RequestMapping("/subscription/api/plan")
public class SubscriptionPlanController {

	@Autowired
	private SubscriptionPlanService service;

	@Autowired
	private SubscriptionPlanHelper helperService;

	@PostMapping
	@PreAuthorize("@perm.canOperatePos()")
	public ResponseEntity<SubscriptionPlanCreateResponse> createPlan(
			@Valid @RequestBody SubscriptionPlanCreateRequest request,
			@RequestHeader(name = "X-User", required = false) String userHeader) {
		UUID createdBy = request.getCreatedBy() != null
				? request.getCreatedBy()
				: AccessContext.actorApplicationUserId();
		SubscriptionPlanCreateResponse resp = service.createPlanWithChildren(request, createdBy);
		return ResponseEntity.ok(resp);
	}

	@PostMapping("/batch")
	@PreAuthorize("@perm.canOperatePos()")
	public ResponseEntity<SubscriptionPlanBatchCreateResponse> createPlans(
			@Valid @RequestBody SubscriptionPlanBatchCreateRequest request,
			@RequestHeader(name = "X-User", required = false) String userHeader) {
		UUID createdBy = request.getCreatedBy() != null
				? request.getCreatedBy()
				: AccessContext.actorApplicationUserId();
		return ResponseEntity.ok(service.createPlans(request, createdBy));
	}

	/**
	 * Preview billing quote line-items by calling the billing vendor API (same payload as v3 finalize
	 * {@code billingQuoteFinalizeSpecs}).
	 */
	@PostMapping("/billing-quote/line-items")
	@PreAuthorize("@perm.canOperatePos()")
	public ResponseEntity<List<BillingQuoteLineItemsResponse>> fetchBillingQuoteLineItems(
			@RequestBody List<BillingQuoteFinalizeSpec> specs) {
		return ResponseEntity.ok(helperService.fetchQuoteLineItems(specs));
	}

	@GetMapping("/{subscriptionPlanId}/detail")
	public ResponseEntity<?> getInvoiceDetail(@PathVariable UUID subscriptionPlanId) {
		try {
			InvoiceDetailDTO dto = service.getSubscriptionDetail(subscriptionPlanId);
			return ResponseEntity.ok(dto);
		} catch (NoSuchElementException e) {
			return ResponseEntity.notFound().build();
		} catch (Exception e) {
			return ResponseEntity.status(500).body(e.getMessage());
		}
	}

	@GetMapping("/by-client-role/{clientRoleId}")
	public ResponseEntity<List<SubscriptionPlanSummaryDTO>> getPlansByClientRole(@PathVariable UUID clientRoleId) {

		List<SubscriptionPlanSummaryDTO> list = service.getClientSubscriptionPlans(clientRoleId);
		return ResponseEntity.ok(list);
	}
}
