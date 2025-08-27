package io.clubone.transaction.controller;

import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import io.clubone.transaction.response.CreateInvoiceResponse;
import io.clubone.transaction.service.TransactionServicev2;
import io.clubone.transaction.v2.vo.InvoiceRequest;
import io.clubone.transaction.v2.vo.InvoiceSummaryDTO;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("v2/api/transactions")
@RequiredArgsConstructor
public class TransactionControllerV2 {

	@Autowired
	private TransactionServicev2 transactionService;

	@PostMapping("/invoice")
	public ResponseEntity<CreateInvoiceResponse> createInvoice(@RequestBody InvoiceRequest request) {
		CreateInvoiceResponse response = transactionService.createInvoice(request);
		return ResponseEntity.ok(response);
	}

	@GetMapping
	public ResponseEntity<List<InvoiceSummaryDTO>> listByClientRole(@RequestParam UUID clientRoleId,
			@RequestParam(required = false) Integer limit, @RequestParam(required = false) Integer offset) {
		return ResponseEntity.ok(transactionService.listInvoicesByClientRole(clientRoleId, limit, offset));
	}
}
