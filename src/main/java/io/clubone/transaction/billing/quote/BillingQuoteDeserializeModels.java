package io.clubone.transaction.billing.quote;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import lombok.Data;

/**
 * Jackson models for billing quote /line-items JSON (subset used for persistence).
 */
public final class BillingQuoteDeserializeModels {

	private BillingQuoteDeserializeModels() {
	}

	@Data
	@JsonIgnoreProperties(ignoreUnknown = true)
	public static class BillingSection {
		private String frequencyCode;
		private Integer intervalCount;
		private Integer billingDayOfMonth;
		private Boolean prorationApplied;
		private String prorationCase;
		private String quoteMode;
		private String chargeTriggerTypeCode;
		private String chargeEndConditionCode;
		private String billingTimingCode;
		private String billingAlignmentCode;
		private String prorationStrategyCode;
		@JsonProperty("proration_source")
		@JsonAlias("prorationSource")
		private String prorationSource;
	}

	@Data
	@JsonIgnoreProperties(ignoreUnknown = true)
	public static class PlanPosDetailSection {
		/** POS catalog {@code agreementId}; use when quote root {@code entityId} is absent. */
		@JsonProperty("agreementId")
		@JsonAlias({ "agreement_id" })
		private UUID agreementId;
		private UUID packagePlanTemplateId;
		private UUID packageItemId;
		private UUID levelId;
		private UUID packageVersionId;
		private UUID packagePlanTemplateTermConfigId;
		private UUID agreementTermId;
		private Integer termIntervalCount;
		private Integer termTotalCycles;
		private String luPlanCode;
		private String luPlanName;
		private String luPlanDescription;
		private LocalDate billingStartDate;
		private LocalDate billingEndDate;
		private List<PackagePriceRow> packagePrices;
		private List<EntitlementRow> entitlements;
		private AgreementTermDetail agreementTermDetail;
	}

	@Data
	@JsonIgnoreProperties(ignoreUnknown = true)
	public static class AgreementTermDetail {
		private Integer durationValue;
		private String durationUnitTypeCode;
	}

	@Data
	@JsonIgnoreProperties(ignoreUnknown = true)
	public static class PackagePriceRow {
		private UUID packagePriceId;
		private UUID packageLocationId;
		private UUID locationLevelId;
		private BigDecimal price;
		private BigDecimal minPrice;
		private BigDecimal maxPrice;
	}

	@Data
	@JsonIgnoreProperties(ignoreUnknown = true)
	public static class EntitlementRow {
		private UUID entitlementModeId;
		private Integer quantityPerCycle;
		private Integer totalEntitlement;
		private Boolean isUnlimited;
		private Integer maxRedemptionsPerDay;
	}

	@Data
	@JsonIgnoreProperties(ignoreUnknown = true)
	public static class QuoteLineItemRow {
		private Integer sequence;
		private String label;
		private LocalDate startDate;
		private LocalDate endDate;
		private LocalDate billingDate;
		private Integer quantity;
		private BigDecimal unitPrice;
		private BigDecimal unitPriceBeforeDiscount;
		private BigDecimal price;
		private BigDecimal taxPct;
		private BigDecimal tax;
		private BigDecimal amount;
		private BigDecimal proratedAmount;
		@JsonProperty("prorated_amount")
		private BigDecimal proratedAmountSnake;
		private Boolean isProrated;
		private String proratedChargeTiming;
		private String disclosureAutoRenewal;
		private String disclosureMinTerm;
		/** Optional billing-period description; may contain parseable dates for schedule bounds. */
		private String periodLabel;
		@JsonProperty("period_label")
		private String periodLabelSnake;
		/** When {@code FEE}, this line is excluded from subscription schedule / snapshot / tax persistence. */
		private String itemGroupCode;
		private Boolean isFeeItem;
		private UUID taxRateId;
		@JsonProperty("tax_rate_id")
		private UUID taxRateIdSnake;
		private List<UUID> taxRateAllocationIds;
		@JsonProperty("tax_rate_allocation_ids")
		private List<UUID> taxRateAllocationIdsSnake;

		public UUID resolvedTaxRateId() {
			return taxRateId != null ? taxRateId : taxRateIdSnake;
		}

		public List<UUID> resolvedTaxRateAllocationIds() {
			List<UUID> a = taxRateAllocationIds != null ? taxRateAllocationIds : taxRateAllocationIdsSnake;
			return a != null ? a : Collections.emptyList();
		}

		public BigDecimal resolvedProratedAmount() {
			if (proratedAmount != null) {
				return proratedAmount;
			}
			return proratedAmountSnake;
		}

		public String resolvedPeriodLabel() {
			if (periodLabel != null && !periodLabel.isBlank()) {
				return periodLabel.trim();
			}
			return periodLabelSnake != null ? periodLabelSnake.trim() : null;
		}

		/** Optional promotions applied to this line (nested under the line item in quote JSON). */
		private List<QuotePromotionRow> promotions;
	}

	/**
	 * Promotion row from quote {@code promotions[]} or nested under a line item.
	 * When used at quote root, {@link #lineSequence} selects the snapshot line by persisted {@code line_sequence}.
	 */
	@Data
	@JsonIgnoreProperties(ignoreUnknown = true)
	public static class QuotePromotionRow {
		@JsonProperty("lineSequence")
		@JsonAlias({ "line_sequence" })
		private Integer lineSequence;
		@JsonProperty("promotionVersionId")
		@JsonAlias({ "promotion_version_id" })
		private UUID promotionVersionId;
		@JsonProperty("promotionEffectId")
		@JsonAlias({ "promotion_effect_id" })
		private UUID promotionEffectId;
		@JsonProperty("cycleStart")
		@JsonAlias({ "cycle_start" })
		private Integer cycleStart;
		@JsonProperty("cycleEnd")
		@JsonAlias({ "cycle_end", "endCycle", "end_cycle" })
		private Integer cycleEnd;
		@JsonProperty("priceCycleBandId")
		@JsonAlias({ "price_cycle_band_id" })
		private UUID priceCycleBandId;
		@JsonProperty("discountTypeCode")
		@JsonAlias({ "discount_type_code" })
		private String discountTypeCode;
		@JsonProperty("discountValue")
		@JsonAlias({ "discount_value" })
		private BigDecimal discountValue;

		public UUID resolvedPromotionVersionId() {
			return promotionVersionId;
		}
	}

	/**
	 * Expected shape of {@code recurring[]} from the quote API (field names may be camelCase or snake_case).
	 */
	@Data
	@JsonIgnoreProperties(ignoreUnknown = true)
	public static class RecurringForecastRow {
		/**
		 * Authoritative billing cycle index from the quote API (e.g. 2–13 when cycle 1 is the prorated line-item
		 * period). Prefer over {@link #cycleNumber}.
		 */
		private Integer billingCycle;
		@JsonProperty("billing_cycle")
		private Integer billingCycleSnake;
		@JsonProperty("cycleNumber")
		private Integer cycleNumber;
		@JsonProperty("cycle_number")
		private Integer cycleNumberSnake;
		/** Short display, e.g. {@code Cycle 2}. */
		private String label;
		@JsonProperty("periodStart")
		private LocalDate periodStart;
		@JsonProperty("period_start")
		private LocalDate periodStartSnake;
		@JsonProperty("periodEnd")
		private LocalDate periodEnd;
		@JsonProperty("period_end")
		private LocalDate periodEndSnake;
		@JsonProperty("billingDate")
		private LocalDate billingDate;
		@JsonProperty("billing_date")
		private LocalDate billingDateSnake;
		private BigDecimal unitPrice;
		@JsonProperty("unit_price")
		private BigDecimal unitPriceSnake;
		private BigDecimal unitPriceBeforeDiscount;
		@JsonProperty("unit_price_before_discount")
		private BigDecimal unitPriceBeforeDiscountSnake;
		private BigDecimal taxPct;
		@JsonProperty("tax_pct")
		private BigDecimal taxPctSnake;
		private BigDecimal taxAmount;
		@JsonProperty("tax_amount")
		private BigDecimal taxAmountSnake;
		@JsonProperty("tax")
		private BigDecimal taxCompact;
		private BigDecimal amount;
		private BigDecimal proratedAmount;
		@JsonProperty("prorated_amount")
		private BigDecimal proratedAmountSnake;
		private LocalDate proratedFromDate;
		@JsonProperty("prorated_from_date")
		private LocalDate proratedFromDateSnake;
		private LocalDate proratedToDate;
		@JsonProperty("prorated_to_date")
		private LocalDate proratedToDateSnake;
		private BigDecimal discountedAmount;
		@JsonProperty("discounted_amount")
		private BigDecimal discountedAmountSnake;
		private String periodLabel;
		@JsonProperty("period_label")
		private String periodLabelSnake;
		@JsonProperty("nextPeriodStart")
		private LocalDate nextPeriodStart;
		private UUID taxRateId;
		@JsonProperty("tax_rate_id")
		private UUID taxRateIdSnake;
		private List<UUID> taxRateAllocationIds;
		@JsonProperty("tax_rate_allocation_ids")
		private List<UUID> taxRateAllocationIdsSnake;

		public int resolvedCycleNumber() {
			if (billingCycle != null) {
				return billingCycle;
			}
			if (billingCycleSnake != null) {
				return billingCycleSnake;
			}
			if (cycleNumber != null) {
				return cycleNumber;
			}
			if (cycleNumberSnake != null) {
				return cycleNumberSnake;
			}
			return 1;
		}

		/** Schedule row label: short {@code label} when present, else {@code periodLabel}. */
		public String resolvedScheduleLabel() {
			if (label != null && !label.isBlank()) {
				return label.trim();
			}
			return resolvedPeriodLabel();
		}

		public LocalDate resolvedPeriodStart() {
			if (periodStart != null) {
				return periodStart;
			}
			if (periodStartSnake != null) {
				return periodStartSnake;
			}
			return nextPeriodStart;
		}

		public LocalDate resolvedPeriodEnd() {
			return periodEnd != null ? periodEnd : periodEndSnake;
		}

		public LocalDate resolvedBillingDate() {
			return billingDate != null ? billingDate : billingDateSnake;
		}

		public BigDecimal resolvedUnitPrice() {
			return unitPrice != null ? unitPrice : unitPriceSnake;
		}

		public BigDecimal resolvedUnitPriceBeforeDiscount() {
			BigDecimal v = unitPriceBeforeDiscount != null ? unitPriceBeforeDiscount : unitPriceBeforeDiscountSnake;
			return v != null ? v : resolvedUnitPrice();
		}

		public BigDecimal resolvedTaxPct() {
			return taxPct != null ? taxPct : taxPctSnake;
		}

		public BigDecimal resolvedTaxAmount() {
			if (taxAmount != null) {
				return taxAmount;
			}
			if (taxAmountSnake != null) {
				return taxAmountSnake;
			}
			return taxCompact;
		}

		public BigDecimal resolvedDiscountedAmount() {
			return discountedAmount != null ? discountedAmount : discountedAmountSnake;
		}

		public BigDecimal resolvedProratedAmount() {
			return proratedAmount != null ? proratedAmount : proratedAmountSnake;
		}

		public LocalDate resolvedProratedFromDate() {
			return proratedFromDate != null ? proratedFromDate : proratedFromDateSnake;
		}

		public LocalDate resolvedProratedToDate() {
			return proratedToDate != null ? proratedToDate : proratedToDateSnake;
		}

		public String resolvedPeriodLabel() {
			String a = periodLabel != null ? periodLabel : periodLabelSnake;
			return a != null ? a.trim() : null;
		}

		public UUID resolvedTaxRateId() {
			return taxRateId != null ? taxRateId : taxRateIdSnake;
		}

		public List<UUID> resolvedTaxRateAllocationIds() {
			List<UUID> a = taxRateAllocationIds != null ? taxRateAllocationIds : taxRateAllocationIdsSnake;
			return a != null ? a : Collections.emptyList();
		}
	}

	@Data
	@JsonIgnoreProperties(ignoreUnknown = true)
	public static class AppliedPricingRow {
		private String unitPriceSource;
		private UUID packagePriceId;
		private UUID priceCycleBandId;
		private Integer cycleNumber;
		/** Band bounds from quote API; persisted to {@code cycle_start} / {@code cycle_end} on snapshot cycle price. */
		@JsonAlias({ "startCycle", "start_cycle" })
		private Integer startCycle;
		@JsonAlias({ "endCycle", "end_cycle" })
		private Integer endCycle;
		private BigDecimal unitPrice;
		private BigDecimal discountedUnitPrice;
		private Integer entitlementQuantityUsed;
		private BigDecimal posPriceMin;
		private BigDecimal posPriceMax;
	}
}
