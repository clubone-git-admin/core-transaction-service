package io.clubone.transaction.salesadvisor;

import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("v2/api/transactions")
@RequiredArgsConstructor
public class SalesAdvisorController {

	private final SalesAdvisorDao salesAdvisorDao;

	@GetMapping("/{invoiceId}/sales-advisor")
	public ResponseEntity<SalesAdvisorAssignmentResponse> get(@PathVariable UUID invoiceId) {
		return ResponseEntity.ok(salesAdvisorDao.getForInvoice(invoiceId));
	}

	@PatchMapping("/{invoiceId}/sales-advisor")
	@PreAuthorize("@perm.canOperatePos()")
	public ResponseEntity<SalesAdvisorAssignmentResponse> update(@PathVariable UUID invoiceId,
			@RequestBody SalesAdvisorUpdateRequest request) {
		return ResponseEntity.ok(salesAdvisorDao.updateForInvoice(invoiceId, request));
	}
}
