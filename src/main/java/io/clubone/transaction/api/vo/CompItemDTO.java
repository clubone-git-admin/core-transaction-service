package io.clubone.transaction.api.vo;

import java.util.UUID;

import lombok.Data;

@Data
public class CompItemDTO {

	private UUID itemId;
	private String itemName;
	private int quantity;
	private String typeName;
	private int billingOptionId;
	private double unitCost;
	private double price;

}
