package io.clubone.transaction.response;

import io.clubone.transaction.vo.InvoiceDTO;
import io.clubone.transaction.vo.TransactionDTO;
import lombok.Data;

@Data
public class InvoiceDetailResponse {
	private InvoiceDTO invoice;
	private TransactionDTO transaction; // null if no txn yet
}
