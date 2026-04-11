package io.clubone.transaction.dao.billing;

import java.util.UUID;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Resolves {@code billing_config} (and package) FK ids from codes returned by the billing quote API.
 */
@Repository
public class BillingEnterpriseLookupDao {

	private final JdbcTemplate jdbc;

	public BillingEnterpriseLookupDao(@Qualifier("cluboneJdbcTemplate") JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	/**
	 * Resolves {@code billing_period_unit_id}. API may send {@code PIF} (paid-in-full) which is not a period
	 * unit — use {@code paidInFullFallbackCode} (e.g. {@code MONTH}) when the direct code is missing.
	 */
	public UUID requireBillingPeriodUnitIdByCode(String frequencyCode, String paidInFullFallbackCode) {
		if (frequencyCode == null || frequencyCode.isBlank()) {
			throw new IllegalArgumentException("frequencyCode is required");
		}
		try {
			return queryBillingPeriodUnitByCode(frequencyCode);
		} catch (IllegalStateException ex) {
			if ("PIF".equalsIgnoreCase(frequencyCode.trim()) && paidInFullFallbackCode != null
					&& !paidInFullFallbackCode.isBlank()) {
				return queryBillingPeriodUnitByCode(paidInFullFallbackCode.trim());
			}
			throw ex;
		}
	}

	private UUID queryBillingPeriodUnitByCode(String code) {
		return requireUuid("""
				SELECT billing_period_unit_id
				FROM billing_config.billing_period_unit
				WHERE (UPPER(TRIM(COALESCE(code, ''))) = UPPER(TRIM(?))
				   OR UPPER(TRIM(COALESCE(display_name, ''))) = UPPER(TRIM(?)))
				  AND COALESCE(is_active, true) = true
				LIMIT 1
				""", code, code);
	}

	public UUID requireChargeTriggerTypeId(String code) {
		return requireUuid("""
				SELECT charge_trigger_type_id
				FROM billing_config.charge_trigger_type
				WHERE UPPER(TRIM(code)) = UPPER(TRIM(?))
				  AND COALESCE(is_active, true) = true
				LIMIT 1
				""", code);
	}

	public UUID requireChargeEndConditionId(String code) {
		return requireUuid("""
				SELECT charge_end_condition_id
				FROM billing_config.charge_end_condition
				WHERE UPPER(TRIM(code)) = UPPER(TRIM(?))
				  AND COALESCE(is_active, true) = true
				LIMIT 1
				""", code);
	}

	public UUID requireBillingTimingId(String code) {
		return requireUuid("""
				SELECT billing_timing_id
				FROM billing_config.billing_timing
				WHERE UPPER(TRIM(code)) = UPPER(TRIM(?))
				  AND COALESCE(is_active, true) = true
				LIMIT 1
				""", code);
	}

	public UUID requireBillingAlignmentId(String code) {
		return requireUuid("""
				SELECT billing_alignment_id
				FROM billing_config.billing_alignment
				WHERE UPPER(TRIM(code)) = UPPER(TRIM(?))
				  AND COALESCE(is_active, true) = true
				LIMIT 1
				""", code);
	}

	public UUID requireProrationStrategyId(String code) {
		return requireUuid("""
				SELECT proration_strategy_id
				FROM billing_config.proration_strategy
				WHERE UPPER(TRIM(code)) = UPPER(TRIM(?))
				  AND COALESCE(is_active, true) = true
				LIMIT 1
				""", code);
	}

	public UUID requireBillingScheduleStatusId(String statusCode) {
		return requireUuid("""
				SELECT billing_schedule_status_id
				FROM billing_config.billing_schedule_status
				WHERE UPPER(TRIM(status_code)) = UPPER(TRIM(?))
				  AND COALESCE(is_active, true) = true
				LIMIT 1
				""", statusCode);
	}

	/** Matches {@code billing_config.subscription_instance_status.status_name} (e.g. ACTIVE). */
	public UUID requireSubscriptionInstanceStatusIdByName(String statusName) {
		return requireUuid("""
				SELECT subscription_instance_status_id
				FROM billing_config.subscription_instance_status
				WHERE UPPER(TRIM(status_name)) = UPPER(TRIM(?))
				  AND COALESCE(is_active, true) = true
				LIMIT 1
				""", statusName);
	}

	/** Matches {@code billing_config.billing_status.status_code} (e.g. PAID). */
	public UUID requireBillingStatusId(String statusCode) {
		return requireUuid("""
				SELECT billing_status_id
				FROM billing_config.billing_status
				WHERE UPPER(TRIM(status_code)) = UPPER(TRIM(?))
				  AND COALESCE(is_active, true) = true
				LIMIT 1
				""", statusCode);
	}

	public UUID findSubscriptionBillingDayRuleIdByTermConfig(UUID packagePlanTemplateTermConfigId) {
		if (packagePlanTemplateTermConfigId == null) {
			return null;
		}
		try {
			return jdbc.queryForObject("""
					SELECT subscription_billing_day_rule_id
					FROM package.package_plan_template_term_config
					WHERE package_plan_template_term_config_id = ?
					  AND COALESCE(is_active, true) = true
					LIMIT 1
					""", UUID.class, packagePlanTemplateTermConfigId);
		} catch (EmptyResultDataAccessException e) {
			return null;
		}
	}

	private UUID requireUuid(String sql, Object... args) {
		try {
			return jdbc.queryForObject(sql, UUID.class, args);
		} catch (EmptyResultDataAccessException e) {
			throw new IllegalStateException("No billing lookup row for: " + java.util.Arrays.toString(args), e);
		}
	}
}
