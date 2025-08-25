package io.clubone.transaction.vo;

import java.math.BigDecimal;
import java.util.UUID;
import lombok.Data;

@Data
public class InvoiceEntityTaxDTO {

	private UUID taxRateId;
	private BigDecimal taxRate;
	private BigDecimal taxAmount;

}
