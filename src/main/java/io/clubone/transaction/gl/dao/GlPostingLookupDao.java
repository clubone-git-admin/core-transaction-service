package io.clubone.transaction.gl.dao;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import io.clubone.transaction.gl.model.PaymentTransactionContext;

/**
 * Optional reference lookups in a new transaction so a bad column/SQL does not abort
 * the caller's transaction (PostgreSQL 25P02).
 */
@Repository
public class GlPostingLookupDao {

	private static final Logger log = LoggerFactory.getLogger(GlPostingLookupDao.class);

	private final JdbcTemplate jdbc;

	public GlPostingLookupDao(@Qualifier("cluboneJdbcTemplate") JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	@Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
	public Optional<UUID> findApplicationIdByLevelId(UUID levelId) {
		if (levelId == null) {
			return Optional.empty();
		}
		return queryOptionalUuid("""
				SELECT loc.application_id
				FROM locations.levels lvl
				JOIN locations.location loc ON loc.location_id = lvl.reference_entity_id
				WHERE lvl.level_id = ?
				LIMIT 1
				""", levelId);
	}

	@Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
	public Optional<UUID> findPaymentMethodTypeIdByCode(String paymentMethodCode) {
		if (paymentMethodCode == null || paymentMethodCode.isBlank()) {
			return Optional.empty();
		}
		String normalized = paymentMethodCode.trim();
		Optional<UUID> byMethodTypeCode = queryOptionalUuid("""
				SELECT payment_gateway_method_type_id
				FROM payment_gateway.lu_payment_gateway_method_type
				WHERE COALESCE(is_active, true) = true
				  AND UPPER(TRIM(method_type_code)) = UPPER(TRIM(?))
				LIMIT 1
				""", normalized);
		if (byMethodTypeCode.isPresent()) {
			return byMethodTypeCode;
		}
		return queryOptionalUuid("""
				SELECT payment_gateway_method_type_id
				FROM payment_gateway.lu_payment_gateway_method_type
				WHERE COALESCE(is_active, true) = true
				  AND UPPER(TRIM(display_name)) = UPPER(TRIM(?))
				LIMIT 1
				""", normalized);
	}

	/**
	 * Loads CPT + payment method context aligned with {@code client_payments} schema
	 * (amount in minor units on CPT; currency from payload if not joinable here).
	 */
	@Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
	public Optional<PaymentTransactionContext> loadPaymentTransactionContext(UUID clientPaymentTransactionId) {
		if (clientPaymentTransactionId == null) {
			return Optional.empty();
		}
		List<PaymentTransactionContext> rows = jdbc.query("""
				SELECT
				    cpt.client_payment_transaction_id,
				    (COALESCE(cpt.amount, 0)::numeric / 100.0)::numeric(18, 2) AS amount,
				    COALESCE(cpt.created_on, now()) AS collected_at,
				    cpm.payment_gateway_method_type_id,
				    NULL::uuid AS payment_gateway_currency_type_id
				FROM client_payments.client_payment_transaction cpt
				LEFT JOIN client_payments.client_payment_method cpm
				  ON cpm.client_payment_method_id = cpt.client_payment_method_id
				WHERE cpt.client_payment_transaction_id = ?
				LIMIT 1
				""", PAYMENT_CTX_MAPPER, clientPaymentTransactionId);
		return rows.stream().findFirst();
	}

	/**
	 * Resolves a value safe for {@code reconciliation.gl_entry.payment_currency_type_id}
	 * (FK → {@code payment_gateway.lu_payment_gateway_currency_type}).
	 * <p>
	 * POS/finalize often sends {@code payment_gateway_supported_currency_id} in
	 * {@code paymentGatewayCurrencyTypeId}; that id is remapped when it is not already
	 * a currency-type PK.
	 */
	@Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
	public Optional<UUID> resolvePaymentCurrencyTypeId(UUID candidateId, UUID clientPaymentTransactionId) {
		if (candidateId != null && isLuPaymentCurrencyTypeId(candidateId)) {
			return Optional.of(candidateId);
		}
		if (candidateId != null) {
			Optional<UUID> fromSupported = findCurrencyTypeIdBySupportedCurrencyId(candidateId);
			if (fromSupported.isPresent()) {
				log.debug("[gl-posting] mapped supported_currency_id={} -> currency_type_id={}", candidateId,
						fromSupported.get());
				return fromSupported;
			}
		}
		if (clientPaymentTransactionId != null) {
			Optional<UUID> fromPayment = findCurrencyTypeIdByClientPaymentTransactionId(clientPaymentTransactionId);
			if (fromPayment.isPresent()) {
				return fromPayment;
			}
		}
		if (candidateId != null) {
			log.warn(
					"[gl-posting] payment currency id {} is not lu_payment_gateway_currency_type and could not be resolved; gl_entry.payment_currency_type_id will be NULL",
					candidateId);
		}
		return Optional.empty();
	}

	private boolean isLuPaymentCurrencyTypeId(UUID id) {
		return queryOptionalUuid("""
				SELECT payment_gateway_currency_type_id
				FROM payment_gateway.lu_payment_gateway_currency_type
				WHERE payment_gateway_currency_type_id = ?
				  AND COALESCE(is_active, true) = true
				LIMIT 1
				""", id).isPresent();
	}

	private Optional<UUID> findCurrencyTypeIdBySupportedCurrencyId(UUID supportedCurrencyId) {
		Optional<UUID> direct = queryOptionalUuid("""
				SELECT payment_gateway_currency_type_id
				FROM payment_gateway.payment_gateway_supported_currency
				WHERE payment_gateway_supported_currency_id = ?
				  AND payment_gateway_currency_type_id IS NOT NULL
				LIMIT 1
				""", supportedCurrencyId);
		if (direct.isPresent() && isLuPaymentCurrencyTypeId(direct.get())) {
			return direct;
		}
		return queryOptionalUuid("""
				SELECT lut.payment_gateway_currency_type_id
				FROM payment_gateway.payment_gateway_supported_currency pgsc
				JOIN payment_gateway.lu_payment_gateway_currency_type lut
				  ON UPPER(TRIM(lut.code)) = UPPER(TRIM(pgsc.currency_code))
				WHERE pgsc.payment_gateway_supported_currency_id = ?
				  AND COALESCE(lut.is_active, true) = true
				LIMIT 1
				""", supportedCurrencyId);
	}

	private Optional<UUID> findCurrencyTypeIdByClientPaymentTransactionId(UUID clientPaymentTransactionId) {
		Optional<UUID> direct = queryOptionalUuid("""
				SELECT pgsc.payment_gateway_currency_type_id
				FROM client_payments.client_payment_transaction cpt
				JOIN client_payments.client_payment_method cpm
				  ON cpm.client_payment_method_id = cpt.client_payment_method_id
				JOIN payment_gateway.payment_gateway_supported_currency pgsc
				  ON pgsc.payment_gateway_supported_currency_id = cpm.payment_gateway_currency_id
				WHERE cpt.client_payment_transaction_id = ?
				  AND pgsc.payment_gateway_currency_type_id IS NOT NULL
				LIMIT 1
				""", clientPaymentTransactionId);
		if (direct.isPresent() && isLuPaymentCurrencyTypeId(direct.get())) {
			return direct;
		}
		return queryOptionalUuid("""
				SELECT lut.payment_gateway_currency_type_id
				FROM client_payments.client_payment_transaction cpt
				JOIN client_payments.client_payment_method cpm
				  ON cpm.client_payment_method_id = cpt.client_payment_method_id
				JOIN payment_gateway.payment_gateway_supported_currency pgsc
				  ON pgsc.payment_gateway_supported_currency_id = cpm.payment_gateway_currency_id
				JOIN payment_gateway.lu_payment_gateway_currency_type lut
				  ON UPPER(TRIM(lut.code)) = UPPER(TRIM(pgsc.currency_code))
				WHERE cpt.client_payment_transaction_id = ?
				  AND COALESCE(lut.is_active, true) = true
				LIMIT 1
				""", clientPaymentTransactionId);
	}

	private static final RowMapper<PaymentTransactionContext> PAYMENT_CTX_MAPPER = new RowMapper<>() {
		@Override
		public PaymentTransactionContext mapRow(ResultSet rs, int rowNum) throws SQLException {
			PaymentTransactionContext ctx = new PaymentTransactionContext();
			ctx.setClientPaymentTransactionId(rs.getObject("client_payment_transaction_id", UUID.class));
			ctx.setAmount(rs.getBigDecimal("amount"));
			Timestamp collected = rs.getTimestamp("collected_at");
			ctx.setCollectedAt(collected == null ? Instant.now() : collected.toInstant());
			ctx.setPaymentMethodTypeId(rs.getObject("payment_gateway_method_type_id", UUID.class));
			ctx.setPaymentCurrencyTypeId(rs.getObject("payment_gateway_currency_type_id", UUID.class));
			return ctx;
		}
	};

	private Optional<UUID> queryOptionalUuid(String sql, Object... args) {
		try {
			return Optional.ofNullable(jdbc.queryForObject(sql, UUID.class, args));
		} catch (EmptyResultDataAccessException ex) {
			return Optional.empty();
		} catch (DataAccessException ex) {
			return Optional.empty();
		}
	}
}
