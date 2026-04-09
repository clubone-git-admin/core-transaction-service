package io.clubone.transaction.v2.vo;

import java.util.List;
import java.util.UUID;

import lombok.Data;

@Data
public class Bundle {
	
	private UUID entityId;
	/** Optional: bundle version FK for the bundle invoice line. */
	private UUID entityVersionId;
    private Integer quantity;
   // private UUID promotionId;
    private List<Item> items;

}
