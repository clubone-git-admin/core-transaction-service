package io.clubone.transaction.gl.dao;

import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

import io.clubone.transaction.gl.model.GlMappingRuleRow;
import io.clubone.transaction.gl.model.PaymentTransactionContext;

public interface GlPostingDao {

	Optional<UUID> findApplicationIdByLevelId(UUID levelId);

	Optional<UUID> findPaymentMethodTypeIdByCode(String paymentMethodCode);

	Optional<PaymentTransactionContext> loadPaymentTransactionContext(UUID clientPaymentTransactionId);

	/**
	 * FK-safe id for {@code reconciliation.gl_entry.payment_currency_type_id}, or empty to store NULL.
	 */
	Optional<UUID> resolvePaymentCurrencyTypeId(UUID candidateId, UUID clientPaymentTransactionId);

	Optional<UUID> findDominantItemCategoryIdForInvoice(UUID invoiceId);

	Optional<GlMappingRuleRow> findBestMappingRule(UUID applicationId, LocalDate asOfDate, UUID paymentMethodTypeId,
			UUID paymentCurrencyTypeId, UUID itemCategoryId, String sourceTypeCode, String transactionTypeCode,
			String journalTypeCode);

	Optional<UUID> findFinancialPeriodId(LocalDate asOfDate);

	Optional<UUID> findLookupIdByCode(String schemaTable, String idColumn, String codeColumn, String code);

	Optional<UUID> findGlPostingStatusIdByCode(String code);

	boolean glEntryPairExists(String debitReferenceCode, String creditReferenceCode);

	int countGlEntriesByReferenceCodes(String debitReferenceCode, String creditReferenceCode);

	void insertGlEntryPair(GlMappingRuleRow rule, UUID financialPeriodId, UUID levelId, UUID entityTypeId,
			String entityReferenceCode, UUID journalTypeId, UUID lineOfBusinessId, UUID paymentCurrencyTypeId,
			java.math.BigDecimal amount, UUID unpostedStatusId, UUID postedStatusId, UUID createdBy,
			String debitReferenceCode, String creditReferenceCode);
}
