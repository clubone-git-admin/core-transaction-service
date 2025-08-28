package io.clubone.transaction.service.impl;

import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import io.clubone.transaction.dao.InvoiceDAO;
import io.clubone.transaction.service.InvoiceService;
import io.clubone.transaction.vo.InvoiceDTO;
import io.clubone.transaction.vo.TransactionDTO;

@Service
public class InvoiceServiceImpl implements InvoiceService {

	@Autowired
	private InvoiceDAO invoiceDAO;

	@Override
	@Transactional(readOnly = true)
	public InvoiceDTO getInvoice(UUID invoiceId) {
		return invoiceDAO.findResolvedById(invoiceId);
	}

	@Override
	@Transactional(readOnly = true)
	public TransactionDTO getLatestTransaction(UUID invoiceId) {
		return invoiceDAO.findLatestTransactionByInvoiceId(invoiceId);
	}
}
