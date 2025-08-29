package io.clubone.transaction.api.vo;

import java.util.UUID;
import lombok.Data;

@Data
public class PaymentOptionDTO {

	private UUID itemId;
	private int billingOptionId;
	private int quantity;
	private double unitCost;
	private double price;

}
