package io.clubone.transaction.api.vo;

import java.util.UUID;
import lombok.Data;

@Data
public class UpsellItemDTO {
	private UUID itemId;
	private double itemPrice;

}
