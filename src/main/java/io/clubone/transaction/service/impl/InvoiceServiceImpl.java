package io.clubone.transaction.service.impl;

import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import io.clubone.transaction.dao.InvoiceDAO;
import io.clubone.transaction.response.InvoiceFullDetailResponse;
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

	@Override
	@Transactional(readOnly = true)
	public InvoiceFullDetailResponse getInvoiceFullDetail(UUID invoiceId, String invoiceNumber) {
		UUID id = resolveInvoiceId(invoiceId, invoiceNumber);
		if (id == null) {
			return null;
		}
		InvoiceDTO invoice = invoiceDAO.findResolvedFullById(id);
		if (invoice == null) {
			return null;
		}
		List<TransactionDTO> txns = invoiceDAO.findAllTransactionsByInvoiceId(id);
		InvoiceFullDetailResponse out = new InvoiceFullDetailResponse();
		out.setInvoice(invoice);
		out.setTransactions(txns != null ? txns : Collections.emptyList());
		return out;
	}

	private UUID resolveInvoiceId(UUID invoiceId, String invoiceNumber) {
		if (invoiceId != null) {
			return invoiceId;
		}
		if (StringUtils.hasText(invoiceNumber)) {
			Optional<UUID> byNum = invoiceDAO.findInvoiceIdByNumber(invoiceNumber.trim());
			return byNum.orElse(null);
		}
		return null;
	}
}
