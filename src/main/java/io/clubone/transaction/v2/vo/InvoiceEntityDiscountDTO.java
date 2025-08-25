package io.clubone.transaction.v2.vo;

import java.math.BigDecimal;
import java.util.UUID;

import lombok.Data;

@Data
public class InvoiceEntityDiscountDTO {

	private UUID discountId;
	private BigDecimal discountRate;
	private BigDecimal discountAmount;
	private UUID calculationTypeId; // passthrough (helps when persisting rows)
	private UUID adjustmentTypeId;

}
