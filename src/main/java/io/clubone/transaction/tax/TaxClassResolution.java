package io.clubone.transaction.tax;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * Resolved tax class + rates before amount computation.
 */
public final class TaxClassResolution {
	private final UUID taxGroupId;
	private final UUID catalogTaxAssignmentId;
	private final UUID matchedLevelId;
	private final boolean taxInclusive;
	private final TaxDeterminationResult.Reason reason;
	private final List<io.clubone.transaction.vo.TaxRateAllocationDTO> allocations;
	private final UUID taxExemptId;

	public TaxClassResolution(
			UUID taxGroupId,
			UUID catalogTaxAssignmentId,
			UUID matchedLevelId,
			boolean taxInclusive,
			TaxDeterminationResult.Reason reason,
			List<io.clubone.transaction.vo.TaxRateAllocationDTO> allocations,
			UUID taxExemptId
	) {
		this.taxGroupId = taxGroupId;
		this.catalogTaxAssignmentId = catalogTaxAssignmentId;
		this.matchedLevelId = matchedLevelId;
		this.taxInclusive = taxInclusive;
		this.reason = reason;
		this.allocations = allocations == null ? List.of() : List.copyOf(allocations);
		this.taxExemptId = taxExemptId;
	}

	public UUID getTaxGroupId() { return taxGroupId; }
	public UUID getCatalogTaxAssignmentId() { return catalogTaxAssignmentId; }
	public UUID getMatchedLevelId() { return matchedLevelId; }
	public boolean isTaxInclusive() { return taxInclusive; }
	public TaxDeterminationResult.Reason getReason() { return reason; }
	public List<io.clubone.transaction.vo.TaxRateAllocationDTO> getAllocations() { return allocations; }
	public UUID getTaxExemptId() { return taxExemptId; }
}
