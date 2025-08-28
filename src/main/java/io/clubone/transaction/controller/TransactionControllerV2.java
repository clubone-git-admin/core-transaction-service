package io.clubone.transaction.controller;

import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import io.clubone.transaction.response.CreateInvoiceResponse;
import io.clubone.transaction.response.InvoiceDetailResponse;
import io.clubone.transaction.service.InvoiceService;
import io.clubone.transaction.service.TransactionServicev2;
import io.clubone.transaction.v2.vo.InvoiceDetailDTO;
import io.clubone.transaction.v2.vo.InvoiceRequest;
import io.clubone.transaction.v2.vo.InvoiceSummaryDTO;
import io.clubone.transaction.vo.InvoiceDTO;
import io.clubone.transaction.vo.TransactionDTO;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("v2/api/transactions")
@RequiredArgsConstructor
public class TransactionControllerV2 {

	@Autowired
	private TransactionServicev2 transactionService;

	@Autowired
	private InvoiceService invoiceService;

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

	@GetMapping("/{invoiceId}/detail")
	public ResponseEntity<?> getInvoiceDetail(@PathVariable UUID invoiceId) {
		try {
			InvoiceDetailDTO dto = transactionService.getInvoiceDetail(invoiceId);
			return ResponseEntity.ok(dto);
		} catch (NoSuchElementException e) {
			return ResponseEntity.notFound().build();
		} catch (Exception e) {
			return ResponseEntity.status(500).body(e.getMessage());
		}
	}

	@GetMapping("/{invoiceId}")
	public ResponseEntity<InvoiceDetailResponse> getInvoiceById(@PathVariable UUID invoiceId) {
		InvoiceDTO invoice = invoiceService.getInvoice(invoiceId);
		if (invoice == null)
			return ResponseEntity.notFound().build();

		TransactionDTO txn = invoiceService.getLatestTransaction(invoiceId);

		InvoiceDetailResponse body = new InvoiceDetailResponse();
		body.setInvoice(invoice);
		body.setTransaction(txn);

		return ResponseEntity.ok(body);
	}

}
