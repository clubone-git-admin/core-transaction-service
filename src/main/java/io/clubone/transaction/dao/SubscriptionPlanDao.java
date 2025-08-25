package io.clubone.transaction.dao;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import io.clubone.transaction.request.SubscriptionPlanCreateRequest;
import io.clubone.transaction.v2.vo.*;

public interface SubscriptionPlanDao {
	UUID insertSubscriptionPlan(SubscriptionPlanCreateRequest req, UUID createdBy);

	int[] batchInsertCyclePrices(UUID planId, List<CyclePriceDTO> rows, UUID createdBy);

	int[] batchInsertDiscountCodes(UUID planId, List<DiscountCodeDTO> rows, UUID createdBy);

	int[] batchInsertEntitlements(UUID planId, List<EntitlementDTO> rows, UUID createdBy);

	int[] batchInsertPromos(UUID planId, List<PromoDTO> rows, UUID createdBy);

	int insertPlanTerm(UUID planId, PlanTermDTO term, UUID createdBy);

	UUID insertSubscriptionInstance(UUID subscriptionPlanId, LocalDate startDate, LocalDate endDate,
			LocalDate nextBillingDate, UUID subscriptionInstanceStatusId, UUID createdBy, Integer currentCycleNumber,
			LocalDate lastBilledOn);

	int insertSubscriptionBillingHistory(BillingHistoryRow row);

// Convenience record/DTO for the history insert
	final class BillingHistoryRow {
		public final UUID subscriptionInstanceId;
		public final long amountChargedMinor; // NOT NULL (int8)
		public final Integer cycleNumber; // nullable
		public final LocalDate paymentDueDate; // nullable
		public final UUID billingStatusId; // nullable
		public final UUID priceCycleBandId; // nullable
		public final UUID clientPaymentIntentId; // nullable
		public final UUID clientPaymentTransactionId; // nullable
		public final UUID invoiceId; // nullable
		public final BigDecimal amountNetExclTax; // nullable
		public final BigDecimal amountTaxTotal; // nullable
		public final BigDecimal amountListExclTax; // nullable
		public final BigDecimal amountDiscountTotalExclTax; // nullable
		public final BigDecimal amountProrationExclTax; // nullable
		public final UUID prorationStrategyId; // nullable
		public final Boolean posOverrideApplied; // nullable (default false in table)
		public final String overrideNote; // nullable
		public final UUID overriddenBy; // nullable
		public final String failureReason; // nullable
		public final String taxBreakdownJson; // nullable (jsonb)

		public BillingHistoryRow(UUID subscriptionInstanceId, long amountChargedMinor, Integer cycleNumber,
				LocalDate paymentDueDate, UUID billingStatusId, UUID priceCycleBandId, UUID clientPaymentIntentId,
				UUID clientPaymentTransactionId, UUID invoiceId, BigDecimal amountNetExclTax, BigDecimal amountTaxTotal,
				BigDecimal amountListExclTax, BigDecimal amountDiscountTotalExclTax, BigDecimal amountProrationExclTax,
				UUID prorationStrategyId, Boolean posOverrideApplied, String overrideNote, UUID overriddenBy,
				String failureReason, String taxBreakdownJson) {
			this.subscriptionInstanceId = subscriptionInstanceId;
			this.amountChargedMinor = amountChargedMinor;
			this.cycleNumber = cycleNumber;
			this.paymentDueDate = paymentDueDate;
			this.billingStatusId = billingStatusId;
			this.priceCycleBandId = priceCycleBandId;
			this.clientPaymentIntentId = clientPaymentIntentId;
			this.clientPaymentTransactionId = clientPaymentTransactionId;
			this.invoiceId = invoiceId;
			this.amountNetExclTax = amountNetExclTax;
			this.amountTaxTotal = amountTaxTotal;
			this.amountListExclTax = amountListExclTax;
			this.amountDiscountTotalExclTax = amountDiscountTotalExclTax;
			this.amountProrationExclTax = amountProrationExclTax;
			this.prorationStrategyId = prorationStrategyId;
			this.posOverrideApplied = posOverrideApplied;
			this.overrideNote = overrideNote;
			this.overriddenBy = overriddenBy;
			this.failureReason = failureReason;
			this.taxBreakdownJson = taxBreakdownJson;
		}
	}

	UUID subscriptionInstanceStatusId(String code); // from client_subscription_billing.lu_subscription_instance_status

	UUID billingStatusId(String code);
}
