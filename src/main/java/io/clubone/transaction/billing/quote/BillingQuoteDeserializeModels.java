package io.clubone.transaction.billing.quote;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

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
	}

	@Data
	@JsonIgnoreProperties(ignoreUnknown = true)
	public static class PlanPosDetailSection {
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
		private Boolean isProrated;
		private String proratedChargeTiming;
		private String disclosureAutoRenewal;
		private String disclosureMinTerm;
	}

	/**
	 * Expected shape of {@code recurring[]} from the quote API (field names may be camelCase or snake_case).
	 */
	@Data
	@JsonIgnoreProperties(ignoreUnknown = true)
	public static class RecurringForecastRow {
		@JsonProperty("cycleNumber")
		private Integer cycleNumber;
		@JsonProperty("cycle_number")
		private Integer cycleNumberSnake;
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
		private BigDecimal taxPct;
		@JsonProperty("tax_pct")
		private BigDecimal taxPctSnake;
		private BigDecimal taxAmount;
		@JsonProperty("tax_amount")
		private BigDecimal taxAmountSnake;
		private BigDecimal amount;
		private BigDecimal discountedAmount;
		@JsonProperty("discounted_amount")
		private BigDecimal discountedAmountSnake;
		private String periodLabel;
		@JsonProperty("period_label")
		private String periodLabelSnake;

		public int resolvedCycleNumber() {
			if (cycleNumber != null) {
				return cycleNumber;
			}
			if (cycleNumberSnake != null) {
				return cycleNumberSnake;
			}
			return 1;
		}

		public LocalDate resolvedPeriodStart() {
			return periodStart != null ? periodStart : periodStartSnake;
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

		public BigDecimal resolvedTaxPct() {
			return taxPct != null ? taxPct : taxPctSnake;
		}

		public BigDecimal resolvedTaxAmount() {
			return taxAmount != null ? taxAmount : taxAmountSnake;
		}

		public BigDecimal resolvedDiscountedAmount() {
			return discountedAmount != null ? discountedAmount : discountedAmountSnake;
		}

		public String resolvedPeriodLabel() {
			return periodLabel != null ? periodLabel : periodLabelSnake;
		}
	}

	@Data
	@JsonIgnoreProperties(ignoreUnknown = true)
	public static class AppliedPricingRow {
		private String unitPriceSource;
		private UUID packagePriceId;
		private UUID priceCycleBandId;
		private Integer cycleNumber;
		private BigDecimal unitPrice;
		private BigDecimal discountedUnitPrice;
		private Integer entitlementQuantityUsed;
		private BigDecimal posPriceMin;
		private BigDecimal posPriceMax;
	}
}
