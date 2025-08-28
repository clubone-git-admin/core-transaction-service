package io.clubone.transaction.service;

import java.util.UUID;

import io.clubone.transaction.vo.InvoiceDTO;
import io.clubone.transaction.vo.TransactionDTO;

public interface InvoiceService {
	
	InvoiceDTO getInvoice(UUID invoiceId);
    TransactionDTO getLatestTransaction(UUID invoiceId);

}
