package io.clubone.transaction.service;

import java.util.List;
import java.util.UUID;

import io.clubone.transaction.response.CreateInvoiceResponse;
import io.clubone.transaction.v2.vo.InvoiceRequest;
import io.clubone.transaction.v2.vo.InvoiceSummaryDTO;

public interface TransactionServicev2 {
	
	CreateInvoiceResponse createInvoice(InvoiceRequest request);
	List<InvoiceSummaryDTO> listInvoicesByClientRole(UUID clientRoleId, Integer limit, Integer offset);

}
