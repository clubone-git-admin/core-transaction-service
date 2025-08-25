package io.clubone.transaction.v2.vo;

import java.util.List;
import java.util.UUID;

import io.clubone.transaction.vo.InvoiceEntityTaxDTO;
import lombok.Data;

@Data
public class Item {

	private UUID entityId;
    private Integer quantity;
    private Double price;
    private UUID pricePlanTemplateId;
    private Boolean upsellItem;
    private List<InvoiceEntityTaxDTO> taxes;
    //List<UUID> discountIds;
    private List<InvoiceEntityPriceBandDTO> priceBands;
}
