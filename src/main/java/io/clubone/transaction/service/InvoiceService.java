package io.clubone.transaction.service;

import java.util.UUID;

import io.clubone.transaction.response.InvoiceFullDetailResponse;
import io.clubone.transaction.vo.InvoiceDTO;
import io.clubone.transaction.vo.TransactionDTO;

public interface InvoiceService {

	InvoiceDTO getInvoice(UUID invoiceId);

	TransactionDTO getLatestTransaction(UUID invoiceId);

	/**
	 * Loads invoice by id or by invoice number (provide exactly one).
	 * Read-only; does not run agreement side effects.
	 */
	InvoiceFullDetailResponse getInvoiceFullDetail(UUID invoiceId, String invoiceNumber);

}
