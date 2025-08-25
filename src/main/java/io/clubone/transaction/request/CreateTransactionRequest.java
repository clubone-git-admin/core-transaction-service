package io.clubone.transaction.request;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import lombok.Data;

@Data
public class CreateTransactionRequest {
	// Invoice + Client Info
	private UUID clientRoleId;
	private UUID invoiceStatusId;
	private UUID clientAgreementId;
	private UUID levelId;

	// Invoice Totals
	private BigDecimal totalAmount;
	private BigDecimal subTotal;
	private BigDecimal taxAmount;
	private BigDecimal discountAmount;
	private boolean isPaid;

	// Payment Info
	private String paymentGatewayCode; // e.g. MANUAL
	private String paymentMethodCode; // e.g. CASH
	private String paymentTypeCode; // e.g. CASH
	private UUID paymentGatewayCurrencyTypeId;
	private String billingAddress;
	private UUID createdBy;

	// Transaction Line Items
	private List<TransactionLineItemRequest> lineItems;
}
