package io.clubone.transaction.dao.impl;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import io.clubone.transaction.dao.SubscriptionPlanDao;
import io.clubone.transaction.dao.utils.DaoUtils;
import io.clubone.transaction.request.SubscriptionPlanCreateRequest;
import io.clubone.transaction.v2.vo.CyclePriceDTO;
import io.clubone.transaction.v2.vo.DiscountCodeDTO;
import io.clubone.transaction.v2.vo.EntitlementDTO;
import io.clubone.transaction.v2.vo.InvoiceDetailRaw;
import io.clubone.transaction.v2.vo.PlanTermDTO;
import io.clubone.transaction.v2.vo.PromoDTO;
import io.clubone.transaction.v2.vo.SubscriptionPlanSummaryDTO;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.BadSqlGrammarException;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.LocalDate;

@Repository
public class SubscriptionPlanDaoImpl implements SubscriptionPlanDao {

	@Autowired
	@Qualifier("cluboneJdbcTemplate")
	private JdbcTemplate cluboneJdbcTemplate;

	private static final Logger log = LoggerFactory.getLogger(SubscriptionBillingDAOImpl.class);

	private static final String INSERT_INSTANCE_SQL = """
			INSERT INTO client_subscription_billing.subscription_instance
			  (subscription_plan_id, start_date, next_billing_date,
			   subscription_instance_status_id, created_by, last_billed_on,
			   end_date, current_cycle_number)
			VALUES (?,?,?,?,?,?,?, COALESCE(?, 0))
			RETURNING subscription_instance_id
			""";

	private static final String INSERT_HISTORY_SQL = """
			INSERT INTO client_subscription_billing.subscription_billing_history
			  (subscription_instance_id, amount_charged_minor,
			   client_payment_intent_id, client_payment_transaction_id,
			   billing_status_id, failure_reason, invoice_id, payment_due_date,
			   cycle_number, amount_net_excl_tax, price_cycle_band_id,
			   pos_override_applied, override_note, overridden_by,
			   proration_strategy_id, amount_proration_excl_tax,
			   amount_list_excl_tax, amount_discount_total_excl_tax,
			   amount_tax_total, tax_breakdown_json)
			VALUES (?,?,?,?,?, ?, ?, ?, ?, ?, ?, COALESCE(?, FALSE), ?, ?, ?, ?, ?, ?, ?, CAST(? AS jsonb))
			""";

	private static final String SQL_BILLING_STATUS = """
			SELECT billing_status_id
			FROM client_subscription_billing.lu_billing_status
			WHERE status_code = ? AND COALESCE(is_active, true) = true
			""";

	private static final String SQL_INSTANCE_STATUS = """
			SELECT subscription_instance_status_id
			FROM client_subscription_billing.lu_subscription_instance_status
			WHERE status_name = ? AND COALESCE(is_active, true) = true
			""";

	private static final String SQL_FIND_CPM_BY_TXN = """
			    SELECT cpt.client_payment_method_id
			    FROM client_payments.client_payment_transaction cpt
			    JOIN "transactions"."transaction" t
			      ON t.client_payment_transaction_id = cpt.client_payment_transaction_id
			    WHERE t.transaction_id = ?
			      AND COALESCE(t.is_active, true) = true
			    LIMIT 1
			""";

	private static final String SQL_PLAN_TERM_INSERT = """
			    INSERT INTO client_subscription_billing.subscription_plan_term
			        (subscription_plan_id, remaining_cycles, end_date, created_by, modified_by)
			    VALUES (?, ?, ?, ?, ?)
			    RETURNING subscription_plan_term_id
			""";

	private static final String SQL_BILLING_DAY_RULE = """
			    SELECT sf.frequency_name, sdr.billing_day
			    FROM client_subscription_billing.lu_subscription_billing_day_rule sdr
			    JOIN client_subscription_billing.lu_subscription_frequency sf
			      ON sf.subscription_frequency_id = sdr.subscription_frequency_id
			    WHERE sdr.subscription_billing_day_rule_id = ?
			      AND (?::uuid IS NULL OR sf.subscription_frequency_id = ?::uuid)
			      AND COALESCE(sf.is_active,  true) = true
			      AND COALESCE(sdr.is_active, true) = true
			    LIMIT 1
			""";

	@Override
	public UUID insertSubscriptionPlan(SubscriptionPlanCreateRequest req, UUID createdBy) {
		final String sql = """
				    INSERT INTO client_subscription_billing.subscription_plan
				      ( client_payment_method_id, subscription_frequency_id, interval_count,
				       subscription_billing_day_rule_id, is_active, created_on, created_by,
				        contract_start_date, contract_end_date)
				    VALUES (?,?,?,?, TRUE, now(), ?, ?, ?)
				    RETURNING subscription_plan_id
				""";
		return DaoUtils.queryForUuid(cluboneJdbcTemplate, sql,  req.getClientPaymentMethodId(),
				req.getSubscriptionFrequencyId(), req.getIntervalCount(), req.getSubscriptionBillingDayRuleId(),
				createdBy,  req.getContractStartDate(), req.getContractEndDate());
	}

	@Override
	public int[] batchInsertCyclePrices(UUID planId, List<CyclePriceDTO> rows, UUID createdBy) {
		if (rows == null || rows.isEmpty())
			return new int[0];
		final String sql = """
				    INSERT INTO client_subscription_billing.subscription_plan_cycle_price
				      (subscription_plan_id, cycle_start, cycle_end, unit_price, price_cycle_band_id,
				       allow_pos_price_override, created_on, created_by,
				       window_override_unit_price, window_overridden_by, window_override_on, window_override_note)
				    VALUES (?,?,?,?,?,?, now(), ?, ?, ?, ?, ?)
				""";
		return cluboneJdbcTemplate.batchUpdate(sql, new BatchPreparedStatementSetter() {
			@Override
			public void setValues(PreparedStatement ps, int i) throws SQLException {
				CyclePriceDTO r = rows.get(i);
				ps.setObject(1, planId);
				ps.setObject(2, r.getCycleStart());
				if (r.getCycleEnd() == null)
					ps.setNull(3, java.sql.Types.INTEGER);
				else
					ps.setInt(3, r.getCycleEnd());
				ps.setBigDecimal(4, r.getUnitPrice());
				if (r.getPriceCycleBandId() == null)
					ps.setNull(5, java.sql.Types.OTHER);
				else
					ps.setObject(5, r.getPriceCycleBandId());
				ps.setBoolean(6, Boolean.TRUE.equals(r.getAllowPosPriceOverride()));
				ps.setObject(7, createdBy);
				if (r.getWindowOverrideUnitPrice() == null)
					ps.setNull(8, java.sql.Types.NUMERIC);
				else
					ps.setBigDecimal(8, r.getWindowOverrideUnitPrice());
				if (r.getWindowOverriddenBy() == null)
					ps.setNull(9, java.sql.Types.OTHER);
				else
					ps.setObject(9, r.getWindowOverriddenBy());
				ps.setTimestamp(10,
						r.getWindowOverrideUnitPrice() == null ? null : Timestamp.from(java.time.Instant.now()));
				ps.setString(11, r.getWindowOverrideNote());
			}

			@Override
			public int getBatchSize() {
				return rows.size();
			}
		});
	}

	@Override
	public int[] batchInsertDiscountCodes(UUID planId, List<DiscountCodeDTO> rows, UUID createdBy) {
		if (rows == null || rows.isEmpty())
			return new int[0];
		final String sql = """
				    INSERT INTO client_subscription_billing.subscription_plan_discount_code
				      (subscription_plan_id, discount_id, cycle_start, cycle_end, price_cycle_band_id,
				       stack_rank, is_active, created_on, created_by)
				    VALUES (?,?,?,?,?,?,?, now(), ?)
				""";
		return cluboneJdbcTemplate.batchUpdate(sql, new BatchPreparedStatementSetter() {
			@Override
			public void setValues(PreparedStatement ps, int i) throws SQLException {
				DiscountCodeDTO r = rows.get(i);
				ps.setObject(1, planId);
				ps.setObject(2, r.getDiscountId());
				ps.setInt(3, r.getCycleStart() == null ? 1 : r.getCycleStart());
				if (r.getCycleEnd() == null)
					ps.setNull(4, java.sql.Types.INTEGER);
				else
					ps.setInt(4, r.getCycleEnd());
				if (r.getPriceCycleBandId() == null)
					ps.setNull(5, java.sql.Types.OTHER);
				else
					ps.setObject(5, r.getPriceCycleBandId());
				ps.setInt(6, r.getStackRank() == null ? 100 : r.getStackRank());
				ps.setBoolean(7, r.getIsActive() == null ? true : r.getIsActive());
				ps.setObject(8, createdBy);
			}

			@Override
			public int getBatchSize() {
				return rows.size();
			}
		});
	}

	@Override
	public int[] batchInsertEntitlements(UUID planId, List<EntitlementDTO> rows, UUID createdBy) {
		if (rows == null || rows.isEmpty())
			return new int[0];
		final String sql = """
				    INSERT INTO client_subscription_billing.subscription_plan_entitlement
				      (subscription_plan_id, entitlement_mode_id, quantity_per_cycle, total_entitlement,
				       is_unlimited, created_on, created_by, modified_on, modified_by, max_redemptions_per_day)
				    VALUES (?,?,?,?,?, now(), ?, now(), ?, ?)
				""";
		return cluboneJdbcTemplate.batchUpdate(sql, new BatchPreparedStatementSetter() {
			@Override
			public void setValues(PreparedStatement ps, int i) throws SQLException {
				EntitlementDTO r = rows.get(i);
				ps.setObject(1, planId);
				ps.setObject(2, r.getEntitlementModeId());
				if (r.getQuantityPerCycle() == null)
					ps.setNull(3, java.sql.Types.INTEGER);
				else
					ps.setInt(3, r.getQuantityPerCycle());
				if (r.getTotalEntitlement() == null)
					ps.setNull(4, java.sql.Types.INTEGER);
				else
					ps.setInt(4, r.getTotalEntitlement());
				ps.setBoolean(5, r.getIsUnlimited() != null && r.getIsUnlimited());
				ps.setObject(6, createdBy);
				ps.setObject(7, createdBy);
				if (r.getMaxRedemptionsPerDay() == null)
					ps.setNull(8, java.sql.Types.INTEGER);
				else
					ps.setInt(8, r.getMaxRedemptionsPerDay());
			}

			@Override
			public int getBatchSize() {
				return rows.size();
			}
		});
	}

	@Override
	public int[] batchInsertPromos(UUID planId, List<PromoDTO> rows, UUID createdBy) {
		if (rows == null || rows.isEmpty())
			return new int[0];
		final String sql = """
				    INSERT INTO client_subscription_billing.subscription_plan_promo
				      (subscription_plan_id, promotion_id, promotion_effect_id, cycle_start, cycle_end,
				       price_cycle_band_id, is_active, created_on, created_by, modified_on, modified_by)
				    VALUES (?,?,?,?,?,?, TRUE, now(), ?, now(), ?)
				""";
		return cluboneJdbcTemplate.batchUpdate(sql, new BatchPreparedStatementSetter() {
			@Override
			public void setValues(PreparedStatement ps, int i) throws SQLException {
				PromoDTO r = rows.get(i);
				ps.setObject(1, planId);
				ps.setObject(2, r.getPromotionId());
				if (r.getPromotionEffectId() == null)
					ps.setNull(3, java.sql.Types.OTHER);
				else
					ps.setObject(3, r.getPromotionEffectId());
				ps.setInt(4, r.getCycleStart());
				if (r.getCycleEnd() == null)
					ps.setNull(5, java.sql.Types.INTEGER);
				else
					ps.setInt(5, r.getCycleEnd());
				if (r.getPriceCycleBandId() == null)
					ps.setNull(6, java.sql.Types.OTHER);
				else
					ps.setObject(6, r.getPriceCycleBandId());
				ps.setObject(7, createdBy);
				ps.setObject(8, createdBy);
			}

			@Override
			public int getBatchSize() {
				return rows.size();
			}
		});
	}

	@Override
	public int insertPlanTerm(UUID planId, PlanTermDTO term, UUID createdBy) {
		if (term == null)
			return 0;
		final String sql = """
				    INSERT INTO client_subscription_billing.subscription_plan_term
				      (subscription_plan_id, remaining_cycles, is_active, created_on, created_by,
				         term_end_date,term_start_date)
				    VALUES (?, ?, ?, now(), ?, ?,now())
				""";
		LocalDate end = term.getEndDate();
		return cluboneJdbcTemplate.update(sql, planId, term.getRemainingCycles(),
				term.getIsActive() == null ? Boolean.TRUE : term.getIsActive(), createdBy,  end);
	}

	@Override
	public UUID insertSubscriptionInstance(UUID subscriptionPlanId, LocalDate startDate, LocalDate endDate,
			LocalDate nextBillingDate, UUID subscriptionInstanceStatusId, UUID createdBy, Integer currentCycleNumber,
			LocalDate lastBilledOn) {
		try {
			return cluboneJdbcTemplate.queryForObject(
					INSERT_INSTANCE_SQL, new Object[] { subscriptionPlanId, startDate, nextBillingDate,
							subscriptionInstanceStatusId, createdBy, lastBilledOn, endDate, currentCycleNumber },
					UUID.class);
		} catch (DataAccessException ex) {
			logDbError("insertSubscriptionInstance", ex);
			throw ex;
		}
	}

	@Override
	public int insertSubscriptionBillingHistory(BillingHistoryRow r) {
		try {
			return cluboneJdbcTemplate.update(conn -> {
				PreparedStatement ps = conn.prepareStatement(INSERT_HISTORY_SQL);
				int i = 1;
				ps.setObject(i++, r.subscriptionInstanceId); // subscription_instance_id
				ps.setLong(i++, r.amountChargedMinor); // amount_charged_minor (NOT NULL)
				if (r.clientPaymentIntentId != null)
					ps.setObject(i++, r.clientPaymentIntentId);
				else
					ps.setNull(i++, Types.OTHER); // client_payment_intent_id
				if (r.clientPaymentTransactionId != null)
					ps.setObject(i++, r.clientPaymentTransactionId);
				else
					ps.setNull(i++, Types.OTHER); // client_payment_transaction_id
				if (r.billingStatusId != null)
					ps.setObject(i++, r.billingStatusId);
				else
					ps.setNull(i++, Types.OTHER); // billing_status_id
				if (r.failureReason != null)
					ps.setString(i++, r.failureReason);
				else
					ps.setNull(i++, Types.VARCHAR); // failure_reason
				if (r.invoiceId != null)
					ps.setObject(i++, r.invoiceId);
				else
					ps.setNull(i++, Types.OTHER); // invoice_id
				if (r.paymentDueDate != null)
					ps.setDate(i++, Date.valueOf(r.paymentDueDate));
				else
					ps.setNull(i++, Types.DATE); // payment_due_date
				if (r.cycleNumber != null)
					ps.setInt(i++, r.cycleNumber);
				else
					ps.setNull(i++, Types.INTEGER); // cycle_number
				if (r.amountNetExclTax != null)
					ps.setBigDecimal(i++, r.amountNetExclTax);
				else
					ps.setNull(i++, Types.NUMERIC); // amount_net_excl_tax
				if (r.priceCycleBandId != null)
					ps.setObject(i++, r.priceCycleBandId);
				else
					ps.setNull(i++, Types.OTHER); // price_cycle_band_id
				if (r.posOverrideApplied != null)
					ps.setBoolean(i++, r.posOverrideApplied);
				else
					ps.setNull(i++, Types.BOOLEAN); // pos_override_applied (COALESCE FALSE)
				if (r.overrideNote != null)
					ps.setString(i++, r.overrideNote);
				else
					ps.setNull(i++, Types.VARCHAR); // override_note
				if (r.overriddenBy != null)
					ps.setObject(i++, r.overriddenBy);
				else
					ps.setNull(i++, Types.OTHER); // overridden_by
				if (r.prorationStrategyId != null)
					ps.setObject(i++, r.prorationStrategyId);
				else
					ps.setNull(i++, Types.OTHER); // proration_strategy_id
				if (r.amountProrationExclTax != null)
					ps.setBigDecimal(i++, r.amountProrationExclTax);
				else
					ps.setNull(i++, Types.NUMERIC); // amount_proration_excl_tax
				if (r.amountListExclTax != null)
					ps.setBigDecimal(i++, r.amountListExclTax);
				else
					ps.setNull(i++, Types.NUMERIC); // amount_list_excl_tax
				if (r.amountDiscountTotalExclTax != null)
					ps.setBigDecimal(i++, r.amountDiscountTotalExclTax);
				else
					ps.setNull(i++, Types.NUMERIC); // amount_discount_total_excl_tax
				if (r.amountTaxTotal != null)
					ps.setBigDecimal(i++, r.amountTaxTotal);
				else
					ps.setNull(i++, Types.NUMERIC); // amount_tax_total
				if (r.taxBreakdownJson != null)
					ps.setString(i++, r.taxBreakdownJson);
				else
					ps.setNull(i++, Types.VARCHAR); // tax_breakdown_json (CASTed to jsonb)
				return ps;
			});
		} catch (DataAccessException ex) {
			logDbError("insertSubscriptionBillingHistory", ex);
			throw ex;
		}
	}

	private void logDbError(String op, DataAccessException ex) {
		Throwable root = ex.getMostSpecificCause();
		if (root instanceof SQLException sql) {
			String badSql = (ex instanceof BadSqlGrammarException b) ? b.getSql() : "<n/a>";
			log.error("[{}] SQLState={}, Code={}, Msg={}, SQL={}", op, sql.getSQLState(), sql.getErrorCode(),
					sql.getMessage(), badSql, ex);
		} else {
			log.error("[{}] {}", op, root != null ? root.getMessage() : ex.getMessage(), ex);
		}
	}

	@Override
	public UUID subscriptionInstanceStatusId(String code) {
		try {
			return cluboneJdbcTemplate.queryForObject(SQL_INSTANCE_STATUS, UUID.class, code);
		} catch (EmptyResultDataAccessException ex) {
			log.error("No subscription_instance_status found for name='{}'", code);
			throw ex;
		}
	}

	@Override
	public UUID billingStatusId(String code) {
		try {
			return cluboneJdbcTemplate.queryForObject(SQL_BILLING_STATUS, UUID.class, code);
		} catch (EmptyResultDataAccessException ex) {
			log.error("No billing_status found for code='{}'", code);
			throw ex;
		}
	}

	@Override
	public Optional<UUID> findClientPaymentMethodIdByTransactionId(UUID transactionId) {
		try {
			UUID id = cluboneJdbcTemplate.queryForObject(SQL_FIND_CPM_BY_TXN, UUID.class, transactionId);
			return Optional.ofNullable(id);
		} catch (EmptyResultDataAccessException e) {
			return Optional.empty();
		}
	}

	@Override
	public UUID insert(UUID subscriptionPlanId, int remainingCycles, LocalDate endDate, UUID createdBy) {
		if (subscriptionPlanId == null)
			throw new IllegalArgumentException("subscriptionPlanId is required");
		if (endDate == null)
			throw new IllegalArgumentException("endDate is required");
		if (remainingCycles <= 0)
			throw new IllegalArgumentException("remainingCycles must be > 0");

		return cluboneJdbcTemplate.queryForObject(SQL_PLAN_TERM_INSERT, UUID.class, subscriptionPlanId, remainingCycles,
				Date.valueOf(endDate), // end_date
				// is
				// DATE
				createdBy, createdBy // set modified_by same as created_by initially
		);
	}

	@Override
	public Optional<BillingRule> findRule(UUID frequencyId, UUID dayRuleId) {
		List<BillingRule> list = cluboneJdbcTemplate.query(SQL_BILLING_DAY_RULE, ps -> {
			ps.setObject(1, dayRuleId);
			ps.setObject(2, frequencyId); // nullable guard in SQL
			ps.setObject(3, frequencyId);
		}, (rs, rn) -> new BillingRule(rs.getString("frequency_name"), rs.getString("billing_day")));
		return list.isEmpty() ? Optional.empty() : Optional.of(list.get(0));
	}

	@Override
	public Optional<InvoiceDetailRaw> loadInvoiceAggregateBySubscriptionPlan(UUID subscriptionPlanId) {
		final String sql = """
				WITH inv AS (
				  SELECT i.*
				  FROM client_subscription_billing.subscription_instance si
				  JOIN client_subscription_billing.subscription_billing_history sbh
				    ON sbh.subscription_instance_id = si.subscription_instance_id
				  JOIN "transactions".invoice i
				    ON i.invoice_id = sbh.invoice_id
				  WHERE si.subscription_plan_id = ?
				)
				SELECT
				  -- invoice
				  i.invoice_id,
				  i.invoice_number,
				  i.invoice_date::date                          AS invoice_date,
				  COALESCE(i.total_amount, 0)                   AS amount,
				  CASE WHEN i.is_paid THEN 0 ELSE COALESCE(i.total_amount, 0) END AS balance_due,
				  0::numeric                                    AS write_off,
				  COALESCE(lis.status_name, 'UNKNOWN')          AS status,
				  NULL::text                                    AS sales_rep,
				  i.level_id                                    AS level_id,

				  -- billing history (latest per invoice)
				  sbh.subscription_instance_id,
				  sbh.amount_gross_incl_tax                     AS amount_gross_incl_tax,

				  -- instance
				  si.subscription_plan_id,
				  si.start_date,
				  si.next_billing_date,
				  si.last_billed_on,
				  si.end_date,
				  si.current_cycle_number,

				  -- plan
				  sp.interval_count,
				  sp.subscription_frequency_id,
				  sp.contract_start_date,
				  sp.contract_end_date,
				  sp.entity_id,
				  sp.entity_type_id,

				  -- frequency
				  lf.frequency_name,

				  -- terms
				  COALESCE(spt.remaining_cycles, 0)             AS remaining_cycles,

				  -- child/parent invoice entities (picked per-invoice)
				  ie.entity_id                                   AS child_entity_id,
				  ie.entity_type_id                              AS child_entity_type_id,
				  iep.entity_id                                  AS parent_entity_id,
				  iep.entity_type_id                             AS parent_entity_type_id,

				  -- template fields
				  bpt.total_cycles                               AS template_total_cycles,
				  bpt.level_id                                   AS template_level_id,

				  -- final total_cycles preference: template -> computed from instance/term
				  COALESCE(
				    bpt.total_cycles,
				    CASE
				      WHEN si.current_cycle_number IS NOT NULL AND spt.remaining_cycles IS NOT NULL
				        THEN si.current_cycle_number + spt.remaining_cycles
				      ELSE NULL
				    END
				  )                                              AS total_cycles

				FROM inv i

				-- latest SBH per invoice
				LEFT JOIN LATERAL (
				  SELECT sbh.*
				  FROM client_subscription_billing.subscription_billing_history sbh
				  WHERE sbh.invoice_id = i.invoice_id
				  ORDER BY
				    sbh.billing_attempt_on DESC NULLS LAST,
				    sbh.payment_due_date   DESC NULLS LAST,
				    sbh.cycle_number       DESC NULLS LAST
				  LIMIT 1
				) sbh ON TRUE

				-- instance/plan/frequency
				LEFT JOIN client_subscription_billing.subscription_instance si
				  ON si.subscription_instance_id = sbh.subscription_instance_id
				LEFT JOIN client_subscription_billing.subscription_plan sp
				  ON sp.subscription_plan_id = si.subscription_plan_id
				LEFT JOIN client_subscription_billing.lu_subscription_frequency lf
				  ON lf.subscription_frequency_id = sp.subscription_frequency_id

				-- latest plan term (if any)
				LEFT JOIN LATERAL (
				  SELECT spt.*
				  FROM client_subscription_billing.subscription_plan_term spt
				  WHERE spt.subscription_plan_id = sp.subscription_plan_id
				  ORDER BY spt.modified_on DESC NULLS LAST
				  LIMIT 1
				) spt ON TRUE

				-- pick ONE invoice_entity per invoice
				LEFT JOIN LATERAL (
				  SELECT ie.*
				  FROM "transactions".invoice_entity ie
				  WHERE ie.invoice_id = i.invoice_id
				  ORDER BY
				    (ie.price_plan_template_id IS NOT NULL) DESC,
				    ie.total_amount DESC NULLS LAST,
				    ie.created_on  DESC NULLS LAST
				  LIMIT 1
				) ie ON TRUE

				-- parent entity (if any)
				LEFT JOIN "transactions".invoice_entity iep
				  ON iep.invoice_entity_id = ie.parent_invoice_entity_id

				-- bundle plan template
				LEFT JOIN bundles_new.bundle_plan_template bpt
				  ON bpt.plan_template_id = ie.price_plan_template_id

				LEFT JOIN "transactions".lu_invoice_status lis
				  ON lis.invoice_status_id = i.invoice_status_id

				ORDER BY i.invoice_date DESC, i.created_on DESC NULLS LAST
				 """;

		try {
			return Optional.ofNullable(cluboneJdbcTemplate.queryForObject(sql, RAW_MAPPER, subscriptionPlanId));
		} catch (EmptyResultDataAccessException ex) {
			return Optional.empty();
		}
	}

	private static final RowMapper<InvoiceDetailRaw> RAW_MAPPER = new RowMapper<>() {
		@Override
		public InvoiceDetailRaw mapRow(ResultSet rs, int rowNum) throws SQLException {
			return new InvoiceDetailRaw(
					// invoice
					(UUID) rs.getObject("invoice_id"), rs.getString("invoice_number"),
					rs.getObject("invoice_date", java.time.LocalDate.class), rs.getBigDecimal("amount"),
					rs.getBigDecimal("balance_due"), rs.getBigDecimal("write_off"), rs.getString("status"),
					rs.getString("sales_rep"), (UUID) rs.getObject("level_id"),

					// billing history
					(UUID) rs.getObject("subscription_instance_id"), rs.getBigDecimal("amount_gross_incl_tax"),

					// instance
					(UUID) rs.getObject("subscription_plan_id"), rs.getObject("start_date", java.time.LocalDate.class),
					rs.getObject("next_billing_date", java.time.LocalDate.class),
					rs.getObject("last_billed_on", java.time.LocalDate.class),
					rs.getObject("end_date", java.time.LocalDate.class), (Integer) rs.getObject("current_cycle_number"),

					// plan
					(Integer) rs.getObject("interval_count"), (UUID) rs.getObject("subscription_frequency_id"),
					rs.getObject("contract_start_date", java.time.LocalDate.class),
					rs.getObject("contract_end_date", java.time.LocalDate.class), (UUID) rs.getObject("entity_id"),
					(UUID) rs.getObject("entity_type_id"),

					// frequency/terms
					rs.getString("frequency_name"), (Integer) rs.getObject("remaining_cycles"),

					// invoice_entity joins
					(UUID) rs.getObject("child_entity_id"), (UUID) rs.getObject("child_entity_type_id"),
					(UUID) rs.getObject("parent_entity_id"), (UUID) rs.getObject("parent_entity_type_id"),

					// template
					(Integer) rs.getObject("template_total_cycles"), (UUID) rs.getObject("template_level_id"),

					// final cycles
					(Integer) rs.getObject("total_cycles"));
		}
	};

	@Override
	public List<SubscriptionPlanSummaryDTO> findClientSubscriptionPlans(UUID clientRoleId) throws DataAccessException {
		final String sql = """

				WITH plans AS (
				  SELECT sp.subscription_plan_id,
				         sp.entity_type_id,
				         sp.entity_id
				  FROM client_subscription_billing.subscription_plan sp
				  JOIN client_payments.client_payment_method cpm
				    ON cpm.client_payment_method_id = sp.client_payment_method_id
				  WHERE cpm.client_role_id = ?   -- pass clientRoleId here
				)
				SELECT
				  -- per instance (guaranteed present)
				  si.subscription_plan_id,
				  si.start_date,
				  si.end_date,
				  COALESCE(lss.status_name, 'UNKNOWN') AS status,
				  p.entity_type_id,
				  p.entity_id,

				  -- parent from latest invoice's picked invoice_entity (guaranteed present)
				  iep.entity_id      AS parent_entity_id,
				  iep.entity_type_id AS parent_entity_type_id,

				  -- level preference: invoice.level_id -> template.level_id
				  COALESCE(inv.level_id, bpt.level_id) AS level_id

				FROM plans p

				-- MUST have an instance -> ensures subscription_plan_id is not null in result
				JOIN client_subscription_billing.subscription_instance si
				  ON si.subscription_plan_id = p.subscription_plan_id

				LEFT JOIN client_subscription_billing.lu_subscription_instance_status lss
				  ON lss.subscription_instance_status_id = si.subscription_instance_status_id

				-- pick latest invoice for this instance (via SBH)
				LEFT JOIN LATERAL (
				  SELECT i.invoice_id, i.level_id
				  FROM client_subscription_billing.subscription_billing_history sbh
				  JOIN "transactions".invoice i
				    ON i.invoice_id = sbh.invoice_id
				  WHERE sbh.subscription_instance_id = si.subscription_instance_id
				  ORDER BY
				    sbh.billing_attempt_on DESC NULLS LAST,
				    sbh.payment_due_date   DESC NULLS LAST,
				    sbh.cycle_number       DESC NULLS LAST
				  LIMIT 1
				) inv ON TRUE

				-- pick ONE invoice_entity from that invoice (prefer rows tied to a plan template)
				LEFT JOIN LATERAL (
				  SELECT ie.*
				  FROM "transactions".invoice_entity ie
				  WHERE ie.invoice_id = inv.invoice_id
				  ORDER BY
				    (ie.price_plan_template_id IS NOT NULL) DESC,
				    ie.total_amount DESC NULLS LAST,
				    ie.created_on  DESC NULLS LAST
				  LIMIT 1
				) ie ON TRUE

				-- MUST have a parent entity -> filters out rows without parent_entity_id
				JOIN "transactions".invoice_entity iep
				  ON iep.invoice_entity_id = ie.parent_invoice_entity_id

				-- template (for level fallback)
				LEFT JOIN bundles_new.bundle_plan_template bpt
				  ON bpt.plan_template_id = ie.price_plan_template_id

				ORDER BY si.start_date DESC NULLS LAST, si.subscription_plan_id;
				            """;

		return cluboneJdbcTemplate.query(sql, (rs, rowNum) -> mapPlanRow(rs), clientRoleId);
	}

	private SubscriptionPlanSummaryDTO mapPlanRow(ResultSet rs) throws SQLException {
		return new SubscriptionPlanSummaryDTO(uuid(rs, "subscription_plan_id"),
				rs.getObject("start_date", LocalDate.class), rs.getObject("end_date", LocalDate.class),
				rs.getString("status"), uuid(rs, "entity_type_id"), uuid(rs, "entity_id"), uuid(rs, "parent_entity_id"),
				uuid(rs, "parent_entity_type_id"), uuid(rs, "level_id"));
	}

	private static UUID uuid(ResultSet rs, String col) throws SQLException {
		Object o = rs.getObject(col);
		return (o == null) ? null : (UUID) o;
		// If driver returns String for UUID, use: return o == null ? null :
		// UUID.fromString(o.toString());
	}

}
