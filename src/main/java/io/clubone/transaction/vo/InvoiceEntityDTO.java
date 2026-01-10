package io.clubone.transaction.vo;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import io.clubone.transaction.v2.vo.InvoiceEntityDiscountDTO;
import io.clubone.transaction.v2.vo.InvoiceEntityPriceBandDTO;
import io.clubone.transaction.v2.vo.InvoiceEntityPromotionDTO;
import lombok.Data;

@Data
public class InvoiceEntityDTO {

	private UUID invoiceEntityId;
	private UUID entityTypeId;
	private UUID entityId;
	private UUID pricePlanTemplateId;
	private String entityDescription;
	private LocalDate contractStartDate;
	private int quantity;
	private BigDecimal unitPrice;
	private BigDecimal discountAmount;
	private BigDecimal taxAmount;
	private BigDecimal totalAmount;
	private UUID parentInvoiceEntityId;
	private String promoCode;
	private boolean isUpsellItem;
	private String entityName;
	private List<InvoiceEntityTaxDTO> taxes;
	private List<InvoiceEntityDiscountDTO> discounts;
	private List<InvoiceEntityPriceBandDTO> priceBands;
	private List<InvoiceEntityPromotionDTO> promotions;

	
}
