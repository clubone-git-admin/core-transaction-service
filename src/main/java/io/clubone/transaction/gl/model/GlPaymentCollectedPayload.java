package io.clubone.transaction.gl.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GlPaymentCollectedPayload {

	public static final String EVENT_TYPE = "PAYMENT_COLLECTED";

	private UUID clientPaymentTransactionId;
	private UUID transactionId;
	private UUID invoiceId;
	private BigDecimal amount;
	private UUID applicationId;
	private UUID levelId;
	private UUID paymentMethodTypeId;
	private UUID paymentCurrencyTypeId;
	private String paymentMethodCode;
	private Instant collectedAt;
	private UUID createdBy;
}
