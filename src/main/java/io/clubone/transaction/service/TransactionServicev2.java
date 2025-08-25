package io.clubone.transaction.service;

import io.clubone.transaction.response.CreateInvoiceResponse;
import io.clubone.transaction.v2.vo.InvoiceRequest;

public interface TransactionServicev2 {
	
	CreateInvoiceResponse createInvoice(InvoiceRequest request);

}
