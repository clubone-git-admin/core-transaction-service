package io.clubone.transaction.controller;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
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
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/transactions")
@RequiredArgsConstructor
public class TransactionController {

    private final TransactionService transactionService;

    @PostMapping("/invoice")
    public ResponseEntity<Map<String, Object>> createInvoice(@RequestBody InvoiceDTO dto) {
        UUID invoiceId = transactionService.createInvoice(dto);
        return ResponseEntity.ok(Map.of("invoiceId", invoiceId));
    }

    @PostMapping("/transaction")
    public ResponseEntity<Map<String, Object>> createTransaction(@RequestBody TransactionDTO dto) {
        UUID txnId = transactionService.createTransaction(dto);
        return ResponseEntity.ok(Map.of("transactionId", txnId));
    }
    
    @PostMapping("/finalize")
    public ResponseEntity<CreateTransactionResponse> createAndFinalizeTransaction(
            @RequestBody CreateTransactionRequest request) {

    	UUID transactionId = transactionService.createAndFinalizeTransaction(request);
        return ResponseEntity.ok(new CreateTransactionResponse(transactionId));
    }
    
    /**
     * Step 1: Create an invoice with status = PENDING_PAYMENT
     */
    @PostMapping("v2/invoice")
    public ResponseEntity<CreateInvoiceResponse> createInvoice(@RequestBody CreateInvoiceRequest request) {
        CreateInvoiceResponse response = transactionService.createInvoice(request);
        return ResponseEntity.ok(response);
    }

    /**
     * Step 2: Finalize transaction after payment is completed
     */
    @PostMapping("v2/finalize")
    public ResponseEntity<FinalizeTransactionResponse> finalizeTransaction(
            @RequestBody FinalizeTransactionRequest request) {
        FinalizeTransactionResponse response = transactionService.finalizeTransaction(request);
        return ResponseEntity.ok(response);
    }
    
    @PostMapping("v3/invoice")
    public ResponseEntity<CreateInvoiceResponse> createInvoiceV3(@RequestBody CreateInvoiceRequestV3 request) {
        CreateInvoiceResponse response = transactionService.createInvoiceV3(request);
        return ResponseEntity.ok(response);
    }

    /**
     * Step 2: Finalize transaction after payment is completed
     */
    @PostMapping("v3/finalize")
    public ResponseEntity<FinalizeTransactionResponse> finalizeTransactionv3(
            @RequestBody FinalizeTransactionRequest request) {
        FinalizeTransactionResponse response = transactionService.finalizeTransactionV3(request);
        return ResponseEntity.ok(response);
    }
    
    @PutMapping("/{transactionId}/client-agreement/{clientAgreementId}")
    public ResponseEntity<?> updateClientAgreementPath(
            @PathVariable UUID transactionId,
            @PathVariable UUID clientAgreementId) {

        boolean updated = transactionService.setClientAgreement(transactionId, clientAgreementId);
        if (!updated) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(Map.of(
                "transactionId", transactionId,
                "clientAgreementId", clientAgreementId,
                "status", "UPDATED"));
    }
    
    @GetMapping("/invoice/{clientRoleId}")
    public List<InvoiceResponseDTO> getInvoicesByClient(@PathVariable("clientRoleId") UUID clientRoleId) {
        return transactionService.getInvoicesByClientRole(clientRoleId);
    }
}
