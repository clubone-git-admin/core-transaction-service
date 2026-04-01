package io.clubone.transaction.controller;

import java.time.LocalDate;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import io.clubone.transaction.response.CreateInvoiceResponse;
import io.clubone.transaction.response.InvoiceDetailResponse;
import io.clubone.transaction.response.InvoiceFullDetailResponse;
import io.clubone.transaction.service.InvoiceService;
import io.clubone.transaction.service.TransactionServicev2;
import io.clubone.transaction.v2.vo.FutureInvoiceRequestDTO;
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
			return ResponseEntity.ok(transactionService.getInvoiceDetail(invoiceId));
		} catch (NoSuchElementException e) {
			return ResponseEntity.notFound().build();
		}
	}

	/**
	 * Full invoice payload (header + line items + all transactions) by id or invoice number.
	 * Provide exactly one of {@code invoiceId} or {@code invoiceNumber}.
	 */
	@GetMapping("/invoice/full")
	public ResponseEntity<?> getInvoiceFullDetail(@RequestParam(required = false) UUID invoiceId,
			@RequestParam(required = false) String invoiceNumber) {
		boolean hasId = invoiceId != null;
		boolean hasNum = StringUtils.hasText(invoiceNumber);
		if (!hasId && !hasNum) {
			return ResponseEntity.badRequest().body("Provide invoiceId or invoiceNumber");
		}
		if (hasId && hasNum) {
			return ResponseEntity.badRequest().body("Provide only one of invoiceId or invoiceNumber");
		}
		InvoiceFullDetailResponse body = invoiceService.getInvoiceFullDetail(invoiceId,
				hasNum ? invoiceNumber.trim() : null);
		if (body == null || body.getInvoice() == null) {
			return ResponseEntity.notFound().build();
		}
		return ResponseEntity.ok(body);
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
	
	@PostMapping("/invoice/{invoiceId}/future")
	  public ResponseEntity<CreateInvoiceResponse> createFutureCycleInvoice(
	      @PathVariable UUID invoiceId,
	      @RequestBody FutureInvoiceRequestDTO body,
	      @RequestHeader(value = "X-Actor-Id", required = false) UUID actorId,
	      @RequestParam UUID clientAgreementId
	  ) {
	    int cycleNumber = body.getCycleNumber();
	    LocalDate billingDate = body.getBillingDate() != null ? body.getBillingDate() : LocalDate.now();

	    CreateInvoiceResponse resp =
	        transactionService.createFutureInvoice(invoiceId, cycleNumber, billingDate, actorId,clientAgreementId);

	    return ResponseEntity.ok(resp);
	  }

}
