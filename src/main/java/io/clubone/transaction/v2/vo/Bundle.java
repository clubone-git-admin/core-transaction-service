package io.clubone.transaction.v2.vo;

import java.util.List;
import java.util.UUID;

import lombok.Data;

@Data
public class Bundle {
	
	private UUID entityId;
    private Integer quantity;
   // private UUID promotionId;
    private List<Item> items;

}
