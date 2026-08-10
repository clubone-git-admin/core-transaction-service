package io.clubone.transaction.tax;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * Immutable result of enterprise tax determination for one line.
 */
public final class TaxDeterminationResult {

	public enum Reason {
		CATALOG_ASSIGNMENT,
		ITEM_TAX_GROUP_FALLBACK,
		CLIENT_PROVIDED,
		EXEMPT_ZERO,
		NO_RATE,
		EXTERNAL_PROVIDER,
		UNKNOWN
	}

	private final UUID taxGroupId;
	private final UUID catalogTaxAssignmentId;
	private final UUID matchedLevelId;
	private final UUID taxExemptId;
	private final boolean taxInclusive;
	private final Reason reason;
	private final String providerCode;
	private final List<TaxComponent> components;
	private final BigDecimal taxableAmount;
	private final BigDecimal taxAmount;

	public TaxDeterminationResult(
			UUID taxGroupId,
			UUID catalogTaxAssignmentId,
			UUID matchedLevelId,
			UUID taxExemptId,
			boolean taxInclusive,
			Reason reason,
			String providerCode,
			List<TaxComponent> components,
			BigDecimal taxableAmount,
			BigDecimal taxAmount
	) {
		this.taxGroupId = taxGroupId;
		this.catalogTaxAssignmentId = catalogTaxAssignmentId;
		this.matchedLevelId = matchedLevelId;
		this.taxExemptId = taxExemptId;
		this.taxInclusive = taxInclusive;
		this.reason = reason == null ? Reason.UNKNOWN : reason;
		this.providerCode = providerCode == null ? "INTERNAL" : providerCode;
		this.components = components == null ? List.of() : List.copyOf(components);
		this.taxableAmount = taxableAmount;
		this.taxAmount = taxAmount;
	}

	public static TaxDeterminationResult zero(Reason reason, UUID taxExemptId) {
		return new TaxDeterminationResult(
				null, null, null, taxExemptId, false, reason, "INTERNAL",
				Collections.emptyList(), BigDecimal.ZERO, BigDecimal.ZERO);
	}

	public UUID getTaxGroupId() { return taxGroupId; }
	public UUID getCatalogTaxAssignmentId() { return catalogTaxAssignmentId; }
	public UUID getMatchedLevelId() { return matchedLevelId; }
	public UUID getTaxExemptId() { return taxExemptId; }
	public boolean isTaxInclusive() { return taxInclusive; }
	public Reason getReason() { return reason; }
	public String getProviderCode() { return providerCode; }
	public List<TaxComponent> getComponents() { return components; }
	public BigDecimal getTaxableAmount() { return taxableAmount; }
	public BigDecimal getTaxAmount() { return taxAmount; }

	public static final class TaxComponent {
		private final UUID taxRateId;
		private final UUID taxRateAllocationId;
		private final BigDecimal percentage;
		private final BigDecimal amount;

		public TaxComponent(UUID taxRateId, UUID taxRateAllocationId, BigDecimal percentage, BigDecimal amount) {
			this.taxRateId = taxRateId;
			this.taxRateAllocationId = taxRateAllocationId;
			this.percentage = percentage;
			this.amount = amount;
		}

		public UUID getTaxRateId() { return taxRateId; }
		public UUID getTaxRateAllocationId() { return taxRateAllocationId; }
		public BigDecimal getPercentage() { return percentage; }
		public BigDecimal getAmount() { return amount; }
	}
}
