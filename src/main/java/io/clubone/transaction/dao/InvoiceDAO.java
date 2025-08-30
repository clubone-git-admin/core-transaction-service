package io.clubone.transaction.dao;

import java.util.UUID;

import io.clubone.transaction.vo.InvoiceDTO;
import io.clubone.transaction.vo.TransactionDTO;

public interface InvoiceDAO {

	InvoiceDTO findResolvedById(UUID invoiceId);

	TransactionDTO findLatestTransactionByInvoiceId(UUID invoiceId);
	
	int updateClientAgreementId(UUID invoiceId, UUID clientAgreementId);

}
