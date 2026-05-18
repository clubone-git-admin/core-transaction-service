package io.clubone.transaction.gl.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import lombok.Data;

@Data
public class PaymentTransactionContext {

	private UUID clientPaymentTransactionId;
	private BigDecimal amount;
	private Instant collectedAt;
	private UUID paymentMethodTypeId;
	private UUID paymentCurrencyTypeId;
	private UUID itemCategoryId;
}
