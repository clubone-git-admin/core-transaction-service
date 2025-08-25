package io.clubone.transaction.controller;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import io.clubone.transaction.response.CreateInvoiceResponse;
import io.clubone.transaction.service.TransactionServicev2;
import io.clubone.transaction.v2.vo.InvoiceRequest;
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

}
