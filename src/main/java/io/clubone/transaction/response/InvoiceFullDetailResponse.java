package io.clubone.transaction.response;

import java.util.List;

import io.clubone.transaction.vo.InvoiceDTO;
import io.clubone.transaction.vo.TransactionDTO;
import lombok.Data;

@Data
public class InvoiceFullDetailResponse {

	private InvoiceDTO invoice;
	/** All payment postings for this invoice, newest first */
	private List<TransactionDTO> transactions;
}
