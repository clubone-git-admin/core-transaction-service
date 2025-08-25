package io.clubone.transaction.vo;

import java.math.BigDecimal;
import java.util.UUID;
import lombok.Data;

@Data
public class TaxRateAllocationDTO {
	private UUID taxRateId;
	private BigDecimal taxRatePercentage; // e.g., 18.00 for 18%
}
