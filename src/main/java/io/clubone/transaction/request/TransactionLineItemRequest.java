package io.clubone.transaction.request;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import io.clubone.transaction.vo.TransactionEntityTaxDTO;
import lombok.Data;

@Data
public class TransactionLineItemRequest {
	private UUID entityTypeId;
	private UUID entityId;
	private boolean isUpsellItem;
	private String entityDescription;
	private int quantity;
	private BigDecimal unitPrice;
	private BigDecimal discountAmount;
	private BigDecimal taxAmount;
	private BigDecimal totalAmount;
	private UUID parentTransactionEntityId;
	private List<TransactionEntityTaxDTO> taxes;
}
