package io.clubone.transaction.controller;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.apache.commons.lang3.StringUtils;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.clubone.transaction.request.CreateInvoiceRequest;
import io.clubone.transaction.request.CreateInvoiceRequestV3;
import io.clubone.transaction.request.CreateTransactionRequest;
import io.clubone.transaction.request.FinalizeTransactionRequest;
import io.clubone.transaction.request.InvoiceResponseDTO;
import io.clubone.transaction.response.CreateInvoiceResponse;
import io.clubone.transaction.response.CreateTransactionResponse;
import io.clubone.transaction.response.FinalizeTransactionResponse;
import io.clubone.transaction.service.TransactionService;
import io.clubone.transaction.vo.InvoiceDTO;
import io.clubone.transaction.vo.TransactionDTO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/transactions")
@RequiredArgsConstructor
@Tag(name = "Transactions", description = "Invoice create and transaction finalize APIs")
public class TransactionController {

	private final TransactionService transactionService;

	@PostMapping("/invoice")
	@PreAuthorize("@perm.canOperatePos()")
	@Operation(summary = "Create invoice (legacy)")
	public ResponseEntity<Map<String, Object>> createInvoice(@RequestBody InvoiceDTO dto) {
		UUID invoiceId = transactionService.createInvoice(dto);
		return ResponseEntity.ok(Map.of("invoiceId", invoiceId));
	}

	@PostMapping("/transaction")
	@PreAuthorize("@perm.canOperatePos()")
	@Operation(summary = "Create transaction (legacy)")
	public ResponseEntity<Map<String, Object>> createTransaction(@RequestBody TransactionDTO dto) {
		UUID txnId = transactionService.createTransaction(dto);
		return ResponseEntity.ok(Map.of("transactionId", txnId));
	}

	@PostMapping("/finalize")
	@PreAuthorize("@perm.canOperatePos()")
	@Operation(summary = "Create and finalize transaction")
	public ResponseEntity<CreateTransactionResponse> createAndFinalizeTransaction(
			@RequestBody CreateTransactionRequest request) {

		UUID transactionId = transactionService.createAndFinalizeTransaction(request);
		return ResponseEntity.ok(new CreateTransactionResponse(transactionId));
	}

	@PostMapping("v2/invoice")
	@PreAuthorize("@perm.canOperatePos()")
	@Operation(summary = "Create invoice v2 (PENDING_PAYMENT)")
	public ResponseEntity<CreateInvoiceResponse> createInvoice(@RequestBody CreateInvoiceRequest request) {
		CreateInvoiceResponse response = transactionService.createInvoice(request);
		return ResponseEntity.ok(response);
	}

	@PostMapping("v2/finalize")
	@PreAuthorize("@perm.canOperatePos()")
	@Operation(summary = "Finalize transaction v2 after payment")
	public ResponseEntity<FinalizeTransactionResponse> finalizeTransaction(
			@RequestBody FinalizeTransactionRequest request) {
		FinalizeTransactionResponse response = transactionService.finalizeTransaction(request);
		return ResponseEntity.ok(response);
	}

	@PostMapping("v3/invoice")
	@PreAuthorize("@perm.canOperatePos()")
	@Operation(summary = "Create invoice v3")
	public ResponseEntity<CreateInvoiceResponse> createInvoiceV3(@RequestBody CreateInvoiceRequestV3 request) {
		CreateInvoiceResponse response = transactionService.createInvoiceV3(request);
		return ResponseEntity.ok(response);
	}

	@PostMapping("v3/finalize")
	@PreAuthorize("@perm.canOperatePosOrRemote()")
	@Operation(summary = "Finalize transaction v3 after payment")
	public ResponseEntity<FinalizeTransactionResponse> finalizeTransactionv3(
			@RequestBody FinalizeTransactionRequest request) {
		FinalizeTransactionResponse response = transactionService.finalizeTransactionV3(request);
		if (StringUtils.isEmpty(response.getMessage()))
			return ResponseEntity.ok(response);
		else
			return ResponseEntity.badRequest().body(response);
	}

	@PutMapping("/{transactionId}/client-agreement/{clientAgreementId}")
	@PreAuthorize("@perm.canOperatePos()")
	@Operation(summary = "Link client agreement to transaction")
	public ResponseEntity<?> updateClientAgreementPath(@PathVariable UUID transactionId,
			@PathVariable UUID clientAgreementId) {

		boolean updated = transactionService.setClientAgreement(transactionId, clientAgreementId);
		if (!updated) {
			return ResponseEntity.notFound().build();
		}
		return ResponseEntity.ok(
				Map.of("transactionId", transactionId, "clientAgreementId", clientAgreementId, "status", "UPDATED"));
	}

	@GetMapping("/invoice/{clientRoleId}")
	@Operation(summary = "List invoices by client role")
	public List<InvoiceResponseDTO> getInvoicesByClient(@PathVariable("clientRoleId") UUID clientRoleId) {
		return transactionService.getInvoicesByClientRole(clientRoleId);
	}
}
