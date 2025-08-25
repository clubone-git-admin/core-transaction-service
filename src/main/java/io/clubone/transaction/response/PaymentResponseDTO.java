package io.clubone.transaction.response;

import lombok.Data;
import java.util.UUID;

@Data
public class PaymentResponseDTO {
	private UUID paymentIntentId;
	private UUID transactionId;
	private String message;
}
