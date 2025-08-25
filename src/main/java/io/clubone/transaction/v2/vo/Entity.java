package io.clubone.transaction.v2.vo;

import java.util.List;
import java.util.UUID;

import io.clubone.transaction.vo.InvoiceEntityTaxDTO;
import lombok.Data;

@Data
public class Entity {

	private String type;
	private UUID entityId;
	private Integer quantity;
	List<UUID> discountIds;
	private UUID promotionId;
	private List<Bundle> bundles; // Only if type == "agreement"
	private List<Item> items; // For type == "bundle" or "agreement"
	// Optional fields only for type == "item"
	private Double price;
	private UUID pricePlanTemplateId;
	private Boolean upsellItem;
	private List<InvoiceEntityTaxDTO> taxes;
	private List<InvoiceEntityDiscountDTO> discounts;

}
