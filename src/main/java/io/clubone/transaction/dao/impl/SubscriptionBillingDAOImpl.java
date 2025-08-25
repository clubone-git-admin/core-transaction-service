package io.clubone.transaction.dao.impl;

import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import io.clubone.transaction.dao.SubscriptionBillingDAO;
import io.clubone.transaction.response.SubscriptionBillingDayRuleDTO;
import io.clubone.transaction.vo.SubscriptionBillingHistoryDTO;
import io.clubone.transaction.vo.SubscriptionInstanceDTO;
import io.clubone.transaction.vo.SubscriptionPlanDTO;
import lombok.RequiredArgsConstructor;

@Repository
@RequiredArgsConstructor
public class SubscriptionBillingDAOImpl implements SubscriptionBillingDAO {

	@Autowired
	@Qualifier("cluboneJdbcTemplate")
	private JdbcTemplate cluboneJdbcTemplate;

	private static final String SQL_INSERT_SUBSCRIPTION_PLAN = """
			INSERT INTO client_subscription_billing.subscription_plan (
			    subscription_plan_id, entity_id, client_payment_method_id, amount,
			    subscription_frequency_id, interval_count, subscription_billing_day_rule_id,
			    is_active, created_on, created_by, entity_type_id
			) VALUES (gen_random_uuid(), ?, ?, ?, ?, ?, ?, true, CURRENT_TIMESTAMP, ?, ?)
			RETURNING subscription_plan_id
			""";

	private static final String SQL_INSERT_SUBSCRIPTION_INSTANCE = """
			INSERT INTO client_subscription_billing.subscription_instance (
			    subscription_instance_id, subscription_plan_id, start_date, next_billing_date,
			    subscription_instance_status_id, created_on, created_by, invoice_id
			) VALUES (gen_random_uuid(), ?, ?, ?, ?, CURRENT_TIMESTAMP, ?, ?)
			RETURNING subscription_instance_id
			""";

	private static final String SQL_INSERT_SUBSCRIPTION_HISTORY = """
			INSERT INTO client_subscription_billing.subscription_billing_history (
			    subscription_billing_history_id, subscription_instance_id, billing_attempt_on, amount,
			    client_payment_intent_id, client_payment_transaction_id,
			    subscription_instance_status_id, failure_reason, created_on
			) VALUES (gen_random_uuid(), ?, CURRENT_TIMESTAMP, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP)
			""";

	private static final String SQL_UPDATE_INSTANCE_AFTER_BILL = """
			UPDATE client_subscription_billing.subscription_instance
			SET last_billed_on = ?, next_billing_date = ?, invoice_id = ?
			WHERE subscription_instance_id = ?
			""";

	private static final String SQL_FREQ_NAME = """
			SELECT frequency_name
			FROM client_subscription_billing.lu_subscription_frequency
			WHERE subscription_frequency_id = ?
			""";

	private static final String SQL_BILLING_DAY_RULE = """
			SELECT billing_day
			FROM client_subscription_billing.lu_subscription_billing_day_rule
			WHERE subscription_billing_day_rule_id = ?
			""";

	private static final String SQL_STATUS_ID = """
			SELECT subscription_instance_status_id
			FROM client_subscription_billing.lu_subscription_instance_status
			WHERE UPPER(status_name) = UPPER(?)
			""";

	@Override
	public UUID insertSubscriptionPlan(SubscriptionPlanDTO dto) {
		return cluboneJdbcTemplate.queryForObject(SQL_INSERT_SUBSCRIPTION_PLAN, UUID.class, dto.getEntityId(),
				dto.getClientPaymentMethodId(), dto.getAmount(), dto.getSubscriptionFrequencyId(),
				dto.getIntervalCount(), dto.getSubscriptionBillingDayRuleId(), dto.getCreatedBy(),
				dto.getEntityTypeId());
	}

	@Override
	public UUID insertSubscriptionInstance(SubscriptionInstanceDTO dto) {
		return cluboneJdbcTemplate.queryForObject(SQL_INSERT_SUBSCRIPTION_INSTANCE, UUID.class,
				dto.getSubscriptionPlanId(), dto.getStartDate(), dto.getNextBillingDate(),
				dto.getSubscriptionInstanceStatusId(), dto.getCreatedBy());
	}

	@Override
	public void insertSubscriptionBillingHistory(SubscriptionBillingHistoryDTO dto) {
		cluboneJdbcTemplate.update(SQL_INSERT_SUBSCRIPTION_HISTORY, dto.getSubscriptionInstanceId(), dto.getAmount(),
				dto.getClientPaymentIntentId(), dto.getClientPaymentTransactionId(),
				dto.getSubscriptionInstanceStatusId(), dto.getFailureReason());
	}

	@Override
	public Optional<String> getFrequencyNameById(UUID subscriptionFrequencyId) {
		return cluboneJdbcTemplate.query(SQL_FREQ_NAME,
				rs -> rs.next() ? Optional.ofNullable(rs.getString("frequency_name")) : Optional.empty(),
				subscriptionFrequencyId);
	}

	@Override
	public Optional<String> getBillingDayRuleValue(UUID subscriptionBillingDayRuleId) {
		return cluboneJdbcTemplate.query(SQL_BILLING_DAY_RULE,
				rs -> rs.next() ? Optional.ofNullable(rs.getString("billing_day")) : Optional.empty(),
				subscriptionBillingDayRuleId);
	}

	@Override
	public UUID getSubscriptionInstanceStatusIdByName(String statusName) {
		return cluboneJdbcTemplate.queryForObject(SQL_STATUS_ID, UUID.class, statusName);
	}

	@Override
	public void markInstanceBilled(UUID subscriptionInstanceId, LocalDate lastBilledOn, LocalDate nextBillingDate,
			UUID invoiceId) {
		cluboneJdbcTemplate.update(SQL_UPDATE_INSTANCE_AFTER_BILL, Date.valueOf(lastBilledOn),
				Date.valueOf(nextBillingDate), invoiceId, subscriptionInstanceId);
	}
	
	 private final RowMapper<SubscriptionBillingDayRuleDTO> rowMapper = new RowMapper<>() {
	        @Override
	        public SubscriptionBillingDayRuleDTO mapRow(ResultSet rs, int rowNum) throws SQLException {
	            SubscriptionBillingDayRuleDTO dto = new SubscriptionBillingDayRuleDTO();
	            dto.setSubscriptionBillingDayRuleId((UUID) rs.getObject("subscription_billing_day_rule_id"));
	            dto.setSubscriptionFrequencyId((UUID) rs.getObject("subscription_frequency_id"));
	            dto.setBillingDay(rs.getString("billing_day"));
	            dto.setDisplayName(rs.getString("display_name"));
	            dto.setDescription(rs.getString("description"));
	            dto.setIsActive(rs.getBoolean("is_active"));
	            dto.setCreatedOn(rs.getObject("created_on", java.time.OffsetDateTime.class));
	            dto.setCreatedBy((UUID) rs.getObject("created_by"));
	            dto.setModifiedOn(rs.getObject("modified_on", java.time.OffsetDateTime.class));
	            dto.setModifiedBy((UUID) rs.getObject("modified_by"));
	            return dto;
	        }
	    };

	    @Override
	    public List<SubscriptionBillingDayRuleDTO> findAll() {
	        String sql = "SELECT subscription_billing_day_rule_id, subscription_frequency_id, billing_day, display_name, description, is_active, created_on, created_by, modified_on, modified_by FROM client_subscription_billing.lu_subscription_billing_day_rule";
	        return cluboneJdbcTemplate.query(sql, rowMapper);
	    }
}
