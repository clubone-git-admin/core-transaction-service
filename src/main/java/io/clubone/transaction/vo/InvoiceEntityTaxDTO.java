package io.clubone.transaction.vo;

import java.math.BigDecimal;
import java.util.UUID;
import lombok.Data;

@Data
public class InvoiceEntityTaxDTO {

	/** From finance.tax_rate; null for POS-only tax if not resolved on read. */
	private UUID taxRateId;
	/** Percentage (e.g. 18.00); may be effective rate computed from line net. */
	private BigDecimal taxRate;
	private BigDecimal taxAmount;
	/** From finance.tax_authority when linked via tax_rate_allocation. */
	private String taxAuthority;
	/** From finance.tax_rate_allocation; null for POS-only tax if not resolved on read. */
	private UUID taxRateAllocationId;
}
