package io.clubone.transaction.helper;

import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import io.clubone.transaction.dao.SubscriptionPlanDao;
import io.clubone.transaction.request.SubscriptionPlanCreateRequest;
import io.clubone.transaction.v2.vo.CyclePriceDTO;
import io.clubone.transaction.v2.vo.DiscountCodeDTO;
import io.clubone.transaction.v2.vo.EntitlementDTO;
import io.clubone.transaction.v2.vo.PlanTermDTO;

@Repository
public class SubscriptionPlanHelper {

	@Autowired
	@Qualifier("cluboneJdbcTemplate")
	private JdbcTemplate cluboneJdbcTemplate;

	@Autowired
	private SubscriptionPlanDao subscriptionPlanDao;

	// Projection specific to this helper (includes plan fields)
	public static class InvoiceEntityPlanRow {
		UUID invoiceEntityId;
		UUID entityId;
		UUID entityTypeId;
		LocalDate contractStartDate;
		LocalDate contractEndDate;
		UUID createdBy;

		UUID subscriptionFrequencyId;
		String subscriptionFrequency;
		UUID subscriptionBillingDayRuleId;
		Integer intervalCount;
		Integer totalCycles;
	}

	/**
	 * Fetch ONLY those invoice entities whose plan template has a subscription
	 * frequency
	 */
	public List<InvoiceEntityPlanRow> fetchInvoiceEntitiesWithPlan(UUID invoiceId, UUID transactionId) {
		final String sql = """
				    SELECT
				        ie.invoice_entity_id,
				        ie.entity_id,
				        ie.entity_type_id,
				        ie.contract_start_date,
				        (ie.contract_start_date + INTERVAL '1 month')::date AS contract_end_date,
				        i.created_by,
				        bpt.subscription_billing_day_rule_id,
				        bpt.subscription_frequency_id,
				        COALESCE(bpt.interval_count, 1) AS interval_count,
				        bpt.total_cycles,
				        sf.frequency_name AS subscription_frequency_name
				    FROM "transaction".invoice_entity ie
				    JOIN "transaction".invoice i
				      ON i.invoice_id = ie.invoice_id
				    JOIN "transaction"."transaction" t
				      ON t.invoice_id = i.invoice_id
				    JOIN bundles_new.bundle_plan_template bpt
				      ON bpt.plan_template_id = ie.price_plan_template_id
				    JOIN client_subscription_billing.lu_subscription_frequency sf
				      ON sf.subscription_frequency_id = bpt.subscription_frequency_id
				    WHERE i.invoice_id = ?
				      AND t.transaction_id = ?
				      AND COALESCE(ie.is_active, true) = true
				      AND COALESCE(bpt.is_active, true) = true
				      AND COALESCE(sf.is_active, true) = true
				      AND bpt.subscription_frequency_id IS NOT NULL
				    ORDER BY ie.created_on, ie.invoice_entity_id
				""";

		return cluboneJdbcTemplate.query(sql, (rs, rn) -> {
			InvoiceEntityPlanRow row = new InvoiceEntityPlanRow();
			row.invoiceEntityId = (UUID) rs.getObject("invoice_entity_id");
			row.entityId = (UUID) rs.getObject("entity_id");
			row.entityTypeId = (UUID) rs.getObject("entity_type_id");

			Timestamp cs = rs.getTimestamp("contract_start_date");
			if (cs != null)
				row.contractStartDate = cs.toLocalDateTime().toLocalDate();

			Timestamp ce = rs.getTimestamp("contract_end_date");
			if (ce != null)
				row.contractEndDate = ce.toLocalDateTime().toLocalDate();

			row.createdBy = (UUID) rs.getObject("created_by");
			row.subscriptionBillingDayRuleId = (UUID) rs.getObject("subscription_billing_day_rule_id");
			row.subscriptionFrequencyId = (UUID) rs.getObject("subscription_frequency_id");
			row.intervalCount = rs.getObject("interval_count") != null ? rs.getInt("interval_count") : 1;

			// preserve NULL for total_cycles (rs.getInt returns 0 if NULL)
			row.totalCycles = (Integer) rs.getObject("total_cycles");

			row.subscriptionFrequency = rs.getString("subscription_frequency_name");
			return row;
		}, invoiceId, transactionId);
	}

	public List<CyclePriceDTO> fetchCyclePrices(UUID invoiceEntityId) {
		final String sql = """
				    SELECT
				        ieb.price_cycle_band_id,
				        ieb.unit_price,
				        ieb.is_price_overridden,
				        bpcb.start_cycle AS cycle_start,
				        bpcb.end_cycle   AS cycle_end,
				        bpcb.allow_pos_price_override,
				        bpcb.down_payment_units
				    FROM "transaction".invoice_entity_price_band  AS ieb
				    JOIN bundles_new.bundle_price_cycle_band      AS bpcb
				      ON bpcb.price_cycle_band_id = ieb.price_cycle_band_id
				    WHERE ieb.invoice_entity_id = ?
				      AND COALESCE(ieb.is_active, true) = true
				    ORDER BY ieb.created_on, bpcb.start_cycle
				""";

		return cluboneJdbcTemplate.query(sql, (rs, rowNum) -> {
			CyclePriceDTO dto = new CyclePriceDTO();
			dto.setPriceCycleBandId((UUID) rs.getObject("price_cycle_band_id"));
			dto.setUnitPrice(rs.getBigDecimal("unit_price"));
			// if your DTO has this field, keep it:
			dto.setAllowPosPriceOverride(rs.getBoolean("is_price_overridden"));

			// new fields
			dto.setCycleStart((Integer) rs.getObject("cycle_start")); // null-safe
			dto.setCycleEnd((Integer) rs.getObject("cycle_end")); // may be null (open-ended)
			dto.setAllowPosPriceOverride(rs.getBoolean("allow_pos_price_override"));
			dto.setDownPaymentUnits((Integer) rs.getObject("down_payment_units"));

			return dto;
		}, invoiceEntityId);
	}

	public List<DiscountCodeDTO> fetchDiscounts(UUID invoiceEntityId) {
		final String sql = """
				    SELECT discount_id, discount_amount, adjustment_type_id, calculation_type_id
				    FROM "transaction".invoice_entity_discount
				    WHERE invoice_entity_id = ?
				      AND COALESCE(is_active, true) = true
				    ORDER BY created_on
				""";
		return cluboneJdbcTemplate.query(sql, (rs, rowNum) -> {
			DiscountCodeDTO dto = new DiscountCodeDTO();
			dto.setDiscountId((UUID) rs.getObject("discount_id"));
			// dto.setDiscountAmount(rs.getBigDecimal("discount_amount"));
			// dto.setAdjustmentTypeId((UUID) rs.getObject("adjustment_type_id"));
			// dto.setCalculationTypeId((UUID) rs.getObject("calculation_type_id"));
			return dto;
		}, invoiceEntityId);
	}

	public List<EntitlementDTO> fetchEntitlements(UUID invoiceEntityId) {
		final String sql = """
				    SELECT
				      e.entitlement_mode_id,
				      e.quantity_per_cycle,
				      e.total_entitlement,
				      COALESCE(e.is_unlimited, false) AS is_unlimited,
				      e.max_redemptions_per_day
				    FROM bundles_new.bundle_plan_template_entitlement e
				    JOIN bundles_new.bundle_plan_template bpt
				      ON bpt.plan_template_id = e.plan_template_id
				    JOIN "transaction".invoice_entity ie
				      ON ie.price_plan_template_id = e.plan_template_id
				    WHERE ie.invoice_entity_id = ?
				      AND COALESCE(bpt.is_active, true) = true
				    ORDER BY e.created_on
				""";

		return cluboneJdbcTemplate.query(sql, (rs, rowNum) -> {
			EntitlementDTO dto = new EntitlementDTO();
			dto.setEntitlementModeId((UUID) rs.getObject("entitlement_mode_id"));
			dto.setQuantityPerCycle((Integer) rs.getObject("quantity_per_cycle")); // may be null
			dto.setTotalEntitlement((Integer) rs.getObject("total_entitlement")); // may be null
			dto.setIsUnlimited(rs.getBoolean("is_unlimited")); // defaulted via COALESCE
			dto.setMaxRedemptionsPerDay((Integer) rs.getObject("max_redemptions_per_day"));
			return dto;
		}, invoiceEntityId);
	}

	/** Build one SubscriptionPlanCreateRequest per qualifying invoice entity */
	public List<SubscriptionPlanCreateRequest> buildRequests(UUID invoiceId, UUID transactionId) {
		List<InvoiceEntityPlanRow> rows = fetchInvoiceEntitiesWithPlan(invoiceId, transactionId);
		UUID cpmId = subscriptionPlanDao.findClientPaymentMethodIdByTransactionId(transactionId).orElse(null);

		return rows.stream().map(r -> {
			SubscriptionPlanCreateRequest req = new SubscriptionPlanCreateRequest();

			// who created the invoice
			req.setCreatedBy(r.createdBy);

			// entity mapping
			req.setEntityId(r.entityId);
			req.setEntityTypeId(r.entityTypeId);
			req.setClientPaymentMethodId(cpmId);

			// plan fields required by your request
			req.setSubscriptionFrequencyId(r.subscriptionFrequencyId);
			req.setSubscriptionBillingDayRuleId(r.subscriptionBillingDayRuleId);
			req.setIntervalCount(r.intervalCount != null ? r.intervalCount : 1);

			// dates (fallback end date if null)
			LocalDate start = r.contractStartDate != null ? r.contractStartDate : LocalDate.now();
			LocalDate end = (r.contractEndDate != null) ? r.contractEndDate
					: computeEndDate(start, r.totalCycles, r.intervalCount, r.subscriptionFrequency);
			req.setContractStartDate(start);
			req.setContractEndDate(end);

			// optional children
			List<CyclePriceDTO> cyclePrices=fetchCyclePrices(r.invoiceEntityId);
			req.setCyclePrices(cyclePrices.stream()
					.filter(cp -> cp.getDownPaymentUnits() == null || cp.getDownPaymentUnits() == 0)
					.collect(Collectors.toList()));
			req.setDiscountCodes(fetchDiscounts(r.invoiceEntityId));
			req.setEntitlements(fetchEntitlements(r.invoiceEntityId));
			// req.setPromos(...);
			PlanTermDTO planterm = new PlanTermDTO();
			planterm.setIsActive(true);
			int downPaymentUnits = cyclePrices.stream()
				    .filter(cp -> cp.getDownPaymentUnits() != null)
				    .mapToInt(cp -> cp.getDownPaymentUnits())
				    .findFirst()
				    .orElse(0);
			planterm.setRemainingCycles(r.totalCycles-downPaymentUnits);
			planterm.setEndDate(end);
			//planterm.setSubscriptionPlanId(cpmId);
			req.setTerm(planterm);

			return req;
		}).toList();
	}

	private static LocalDate computeEndDate(LocalDate start, Integer totalCycles, Integer intervalCount,
			String subscriptionFrequencyName) {
		if (start == null)
			start = LocalDate.now();

// If you treat null totalCycles as open-ended, return null here instead of 1
		int cycles = (totalCycles != null && totalCycles > 0) ? totalCycles : 1;
		int interval = (intervalCount != null && intervalCount > 0) ? intervalCount : 1;

		String f = subscriptionFrequencyName == null ? "" : subscriptionFrequencyName.trim().toUpperCase();

		switch (f) {
		case "DAILY":
			return start.plusDays((long) cycles * interval);
		case "WEEKLY":
			return start.plusWeeks((long) cycles * interval);
		case "MONTHLY":
			return start.plusMonths((long) cycles * interval);
		case "YEARLY":
			return start.plusYears((long) cycles * interval);
		case "QUARTERLY": // treat as 3 months per cycle
			return start.plusMonths((long) cycles * interval * 3);
		default:
// Sensible fallback; you can throw instead if you want to force valid values
			return start.plusMonths((long) cycles * interval);
		}
	}
}
