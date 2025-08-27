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
import io.clubone.transaction.v2.vo.PlanTermDTO;
import io.clubone.transaction.v2.vo.PromoDTO;

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
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.Date;
import java.sql.PreparedStatement;
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
			    JOIN "transaction"."transaction" t
			      ON t.client_payment_transaction_id = cpt.client_payment_transaction_id
			    WHERE t.transaction_id = ?
			      AND COALESCE(t.is_active, true) = true
			    LIMIT 1
			""";

	@Override
	public UUID insertSubscriptionPlan(SubscriptionPlanCreateRequest req, UUID createdBy) {
		final String sql = """
				    INSERT INTO client_subscription_billing.subscription_plan
				      (entity_id, client_payment_method_id, subscription_frequency_id, interval_count,
				       subscription_billing_day_rule_id, is_active, created_on, created_by,
				       entity_type_id, contract_start_date, contract_end_date)
				    VALUES (?,?,?,?,?, TRUE, now(), ?, ?, ?, ?)
				    RETURNING subscription_plan_id
				""";
		return DaoUtils.queryForUuid(cluboneJdbcTemplate, sql, req.getEntityId(), req.getClientPaymentMethodId(),
				req.getSubscriptionFrequencyId(), req.getIntervalCount(), req.getSubscriptionBillingDayRuleId(),
				createdBy, req.getEntityTypeId(), req.getContractStartDate(), req.getContractEndDate());
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
				       modified_on, modified_by, end_date)
				    VALUES (?, ?, ?, now(), ?, now(), ?, ?)
				""";
		LocalDate end = term.getEndDate();
		return cluboneJdbcTemplate.update(sql, planId, term.getRemainingCycles(),
				term.getIsActive() == null ? Boolean.TRUE : term.getIsActive(), createdBy, createdBy, end);
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
}
