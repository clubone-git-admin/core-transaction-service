package io.clubone.transaction.gl.dao.impl;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import io.clubone.transaction.gl.dao.GlPostingDao;
import io.clubone.transaction.gl.dao.GlPostingLookupDao;
import io.clubone.transaction.gl.model.GlMappingRuleRow;
import io.clubone.transaction.gl.model.PaymentTransactionContext;
import io.clubone.transaction.security.AccessContext;
import io.clubone.transaction.security.TenantContext;
import io.clubone.transaction.security.UnauthorizedException;

@Repository
public class GlPostingDaoImpl implements GlPostingDao {

	private final JdbcTemplate jdbc;
	private final GlPostingLookupDao lookupDao;

	public GlPostingDaoImpl(@Qualifier("cluboneJdbcTemplate") JdbcTemplate jdbc, GlPostingLookupDao lookupDao) {
		this.jdbc = jdbc;
		this.lookupDao = lookupDao;
	}

	@Override
	public Optional<UUID> findApplicationIdByLevelId(UUID levelId) {
		return lookupDao.findApplicationIdByLevelId(levelId);
	}

	@Override
	public Optional<UUID> findPaymentMethodTypeIdByCode(String paymentMethodCode) {
		return lookupDao.findPaymentMethodTypeIdByCode(paymentMethodCode);
	}

	@Override
	public Optional<PaymentTransactionContext> loadPaymentTransactionContext(UUID clientPaymentTransactionId) {
		return lookupDao.loadPaymentTransactionContext(clientPaymentTransactionId);
	}

	@Override
	public Optional<UUID> resolvePaymentCurrencyTypeId(UUID candidateId, UUID clientPaymentTransactionId) {
		return lookupDao.resolvePaymentCurrencyTypeId(candidateId, clientPaymentTransactionId);
	}

	@Override
	public Optional<UUID> findDominantItemCategoryIdForInvoice(UUID invoiceId) {
		if (invoiceId == null) {
			return Optional.empty();
		}
		return queryOptionalUuid("""
				SELECT i.item_category_id
				FROM transactions.invoice_entity ie
				JOIN transactions.invoice inv ON inv.invoice_id = ie.invoice_id
				JOIN transactions.lu_entity_type et ON et.entity_type_id = ie.entity_type_id
				JOIN items.item i ON i.item_id = ie.entity_id
				WHERE ie.invoice_id = ?
				  AND ie.application_id = inv.application_id
				  AND UPPER(TRIM(et.entity_type)) = 'ITEM'
				  AND i.item_category_id IS NOT NULL
				ORDER BY COALESCE(ie.total_amount, 0) DESC NULLS LAST
				LIMIT 1
				""", invoiceId);
	}

	@Override
	public Optional<GlMappingRuleRow> findBestMappingRule(UUID applicationId, LocalDate asOfDate,
			UUID paymentMethodTypeId, UUID paymentCurrencyTypeId, UUID itemCategoryId, String sourceTypeCode,
			String transactionTypeCode, String journalTypeCode) {
		UUID appId = resolveApplicationId(applicationId);
		List<GlMappingRuleRow> rows = jdbc.query("""
				SELECT
				    r.gl_mapping_rule_id,
				    r.debit_gl_code_id,
				    r.credit_gl_code_id,
				    r.journal_type_id,
				    r.line_of_business_id,
				    COALESCE(r.auto_post, false) AS auto_post
				FROM reconciliation.gl_mapping_rule r
				JOIN reconciliation.lu_gl_source_type st
				  ON st.gl_source_type_id = r.gl_source_type_id
				JOIN reconciliation.lu_gl_transaction_type tt
				  ON tt.gl_transaction_type_id = r.gl_transaction_type_id
				LEFT JOIN reconciliation.lu_journal_type jt
				  ON jt.journal_type_id = r.journal_type_id
				WHERE COALESCE(r.is_active, true) = true
				  AND r.deleted_on IS NULL
				  AND UPPER(TRIM(st.code)) = UPPER(TRIM(?))
				  AND UPPER(TRIM(tt.code)) = UPPER(TRIM(?))
				  AND r.application_id = ?
				  AND (r.effective_from IS NULL OR r.effective_from <= ?)
				  AND (r.effective_to IS NULL OR r.effective_to >= ?)
				  AND (r.payment_method_type_id IS NULL
				       OR (?::uuid IS NOT NULL AND r.payment_method_type_id = ?::uuid))
				  AND (r.payment_currency_type_id IS NULL
				       OR (?::uuid IS NOT NULL AND r.payment_currency_type_id = ?::uuid))
				  AND (r.item_category_id IS NULL
				       OR (?::uuid IS NOT NULL AND r.item_category_id = ?::uuid))
				  AND (r.journal_type_id IS NULL OR UPPER(TRIM(jt.code)) = UPPER(TRIM(?)))
				ORDER BY r.match_rank ASC, r.created_on DESC
				LIMIT 1
				""", MAPPING_RULE_MAPPER, sourceTypeCode, transactionTypeCode, appId, asOfDate, asOfDate,
				paymentMethodTypeId, paymentMethodTypeId, paymentCurrencyTypeId, paymentCurrencyTypeId,
				itemCategoryId, itemCategoryId, journalTypeCode);
		return rows.stream().findFirst();
	}

	/**
	 * Prefer AccessContext when actor context is present (request path); otherwise use the
	 * caller-resolved applicationId (admin/async via findApplicationIdByLevelId).
	 */
	private static UUID resolveApplicationId(UUID applicationId) {
		if (TenantContext.get() != null) {
			return AccessContext.applicationId();
		}
		if (applicationId != null) {
			return applicationId;
		}
		throw new UnauthorizedException("applicationId is required");
	}

	private static final RowMapper<GlMappingRuleRow> MAPPING_RULE_MAPPER = new RowMapper<>() {
		@Override
		public GlMappingRuleRow mapRow(ResultSet rs, int rowNum) throws SQLException {
			GlMappingRuleRow row = new GlMappingRuleRow();
			row.setGlMappingRuleId(rs.getObject("gl_mapping_rule_id", UUID.class));
			row.setDebitGlCodeId(rs.getObject("debit_gl_code_id", UUID.class));
			row.setCreditGlCodeId(rs.getObject("credit_gl_code_id", UUID.class));
			row.setJournalTypeId(rs.getObject("journal_type_id", UUID.class));
			row.setLineOfBusinessId(rs.getObject("line_of_business_id", UUID.class));
			row.setAutoPost(rs.getBoolean("auto_post"));
			return row;
		}
	};

	@Override
	public Optional<UUID> findFinancialPeriodId(LocalDate asOfDate) {
		if (asOfDate == null) {
			return Optional.empty();
		}
		return queryOptionalUuid("""
				SELECT financial_period_id
				FROM reconciliation.financial_period
				WHERE COALESCE(is_active, true) = true
				  AND ? BETWEEN period_start_date AND period_end_date
				ORDER BY period_start_date DESC
				LIMIT 1
				""", asOfDate);
	}

	@Override
	public Optional<UUID> findLookupIdByCode(String schemaTable, String idColumn, String codeColumn, String code) {
		if (code == null || code.isBlank()) {
			return Optional.empty();
		}
		String sql = "SELECT " + idColumn + " FROM " + schemaTable + " WHERE UPPER(TRIM(" + codeColumn
				+ ")) = UPPER(TRIM(?)) AND COALESCE(is_active, true) = true LIMIT 1";
		return queryOptionalUuid(sql, code);
	}

	@Override
	public Optional<UUID> findGlPostingStatusIdByCode(String code) {
		return findLookupIdByCode("reconciliation.lu_gl_posting_status", "gl_posting_status_id", "code", code);
	}

	@Override
	public boolean glEntryPairExists(String debitReferenceCode, String creditReferenceCode) {
		Integer count = jdbc.queryForObject("""
				SELECT COUNT(*)::int
				FROM reconciliation.gl_entry
				WHERE gl_entry_reference_code IN (?, ?)
				""", Integer.class, debitReferenceCode, creditReferenceCode);
		return count != null && count >= 2;
	}

	@Override
	public int countGlEntriesByReferenceCodes(String debitReferenceCode, String creditReferenceCode) {
		Integer count = jdbc.queryForObject("""
				SELECT COUNT(*)::int
				FROM reconciliation.gl_entry
				WHERE gl_entry_reference_code IN (?, ?)
				""", Integer.class, debitReferenceCode, creditReferenceCode);
		return count == null ? 0 : count;
	}

	private Optional<UUID> queryOptionalUuid(String sql, Object... args) {
		try {
			return Optional.ofNullable(jdbc.queryForObject(sql, UUID.class, args));
		} catch (EmptyResultDataAccessException ex) {
			return Optional.empty();
		} catch (DataAccessException ex) {
			return Optional.empty();
		}
	}

	@Override
	public void insertGlEntryPair(GlMappingRuleRow rule, UUID financialPeriodId, UUID levelId, UUID entityTypeId,
			String entityReferenceCode, UUID journalTypeId, UUID lineOfBusinessId,
			UUID paymentCurrencyTypeId, BigDecimal amount, UUID unpostedStatusId, UUID postedStatusId,
			UUID createdBy, String debitReferenceCode, String creditReferenceCode) {
		BigDecimal normalized = amount == null ? BigDecimal.ZERO : amount.setScale(2, RoundingMode.HALF_UP);
		if (normalized.compareTo(BigDecimal.ZERO) <= 0) {
			throw new IllegalArgumentException("GL posting amount must be positive");
		}
		UUID statusId = rule.isAutoPost() ? postedStatusId : unpostedStatusId;
		Timestamp postedAt = rule.isAutoPost() ? Timestamp.from(Instant.now()) : null;

		jdbc.update("""
				INSERT INTO reconciliation.gl_entry (
				    gl_entry_id, gl_entry_reference_code, entity_id, entity_reference_code,
				    financial_period_id, level_id, debit_amount, credit_amount,
				    gl_posting_status_id, posted_at, created_on, created_by,
				    gl_code_id, gl_mapping_rule_id, journal_type_id, line_of_business_id,
				    payment_currency_type_id, entity_type_id
				) VALUES (
				    gen_random_uuid(), ?, NULL, ?,
				    ?, ?, ?, 0,
				    ?, ?, now(), ?,
				    ?, ?, ?, ?,
				    ?, ?
				)
				""", debitReferenceCode, entityReferenceCode, financialPeriodId, levelId, normalized, statusId,
				postedAt, createdBy, rule.getDebitGlCodeId(), rule.getGlMappingRuleId(), journalTypeId,
				lineOfBusinessId, paymentCurrencyTypeId, entityTypeId);

		jdbc.update("""
				INSERT INTO reconciliation.gl_entry (
				    gl_entry_id, gl_entry_reference_code, entity_id, entity_reference_code,
				    financial_period_id, level_id, debit_amount, credit_amount,
				    gl_posting_status_id, posted_at, created_on, created_by,
				    gl_code_id, gl_mapping_rule_id, journal_type_id, line_of_business_id,
				    payment_currency_type_id, entity_type_id
				) VALUES (
				    gen_random_uuid(), ?, NULL, ?,
				    ?, ?, 0, ?,
				    ?, ?, now(), ?,
				    ?, ?, ?, ?,
				    ?, ?
				)
				""", creditReferenceCode, entityReferenceCode, financialPeriodId, levelId, normalized, statusId,
				postedAt, createdBy, rule.getCreditGlCodeId(), rule.getGlMappingRuleId(), journalTypeId,
				lineOfBusinessId, paymentCurrencyTypeId, entityTypeId);
	}
}
