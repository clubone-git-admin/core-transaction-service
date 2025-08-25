package io.clubone.transaction.service;

import java.util.List;
import java.util.UUID;
import io.clubone.transaction.request.CreateInvoiceRequest;
import io.clubone.transaction.request.CreateInvoiceRequestV3;
import io.clubone.transaction.request.CreateTransactionRequest;
import io.clubone.transaction.request.FinalizeTransactionRequest;
import io.clubone.transaction.request.InvoiceResponseDTO;
import io.clubone.transaction.response.CreateInvoiceResponse;
import io.clubone.transaction.response.FinalizeTransactionResponse;
import io.clubone.transaction.vo.InvoiceDTO;
import io.clubone.transaction.vo.InvoiceEntityRow;
import io.clubone.transaction.vo.InvoiceFlatRow;
import io.clubone.transaction.vo.TransactionDTO;

public interface TransactionService {

	UUID createInvoice(InvoiceDTO dto);

	UUID createTransaction(TransactionDTO dto);

	UUID createAndFinalizeTransaction(CreateTransactionRequest request);

	CreateInvoiceResponse createInvoice(CreateInvoiceRequest request);

	FinalizeTransactionResponse finalizeTransaction(FinalizeTransactionRequest request);

	CreateInvoiceResponse createInvoiceV3(CreateInvoiceRequestV3 request);

	FinalizeTransactionResponse finalizeTransactionV3(FinalizeTransactionRequest request);

	boolean setClientAgreement(UUID transactionId, UUID clientAgreementId);

	List<InvoiceResponseDTO> getInvoicesByClientRole(UUID clientRoleId);

}
