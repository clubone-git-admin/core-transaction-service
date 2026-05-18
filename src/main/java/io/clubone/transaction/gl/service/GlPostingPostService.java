package io.clubone.transaction.gl.service;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import io.clubone.transaction.gl.config.GlPostingProperties;
import io.clubone.transaction.gl.dao.GlPostingDao;
import io.clubone.transaction.gl.model.GlMappingRuleRow;
import io.clubone.transaction.gl.model.GlPaymentCollectedPayload;
import io.clubone.transaction.gl.model.PaymentTransactionContext;

@Service
public class GlPostingPostService {

	private static final Logger log = LoggerFactory.getLogger(GlPostingPostService.class);

	private final GlPostingDao glPostingDao;
	private final GlPostingProperties properties;

	public GlPostingPostService(GlPostingDao glPostingDao, GlPostingProperties properties) {
		this.glPostingDao = glPostingDao;
		this.properties = properties;
	}

	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public void postPaymentCollected(GlPaymentCollectedPayload payload) {
		UUID cptId = payload.getClientPaymentTransactionId();
		String debitRef = "PAY-CPT-" + cptId + "-DR";
		String creditRef = "PAY-CPT-" + cptId + "-CR";
		int existingLines = glPostingDao.countGlEntriesByReferenceCodes(debitRef, creditRef);
		if (existingLines >= 2) {
			log.info("[gl-posting] idempotent skip existing gl_entry cpt={}", cptId);
			return;
		}
		if (existingLines == 1) {
			throw new IllegalStateException(
					"Unbalanced GL entry for cpt=" + cptId + " (only one of DR/CR exists); manual cleanup required");
		}

		PaymentTransactionContext ctx = glPostingDao.loadPaymentTransactionContext(cptId)
				.orElseThrow(() -> new IllegalStateException("Payment transaction not found: " + cptId));

		BigDecimal amount = payload.getAmount() != null ? payload.getAmount() : ctx.getAmount();
		if (amount == null || amount.signum() <= 0) {
			throw new IllegalStateException("Payment amount missing or non-positive for cpt=" + cptId);
		}

		final UUID applicationId = resolveApplicationId(payload);

		UUID paymentMethodTypeId = coalesce(payload.getPaymentMethodTypeId(), ctx.getPaymentMethodTypeId());
		if (paymentMethodTypeId == null && payload.getPaymentMethodCode() != null) {
			paymentMethodTypeId = glPostingDao.findPaymentMethodTypeIdByCode(payload.getPaymentMethodCode())
					.orElse(null);
		}
		UUID paymentCurrencyTypeId = glPostingDao
				.resolvePaymentCurrencyTypeId(coalesce(payload.getPaymentCurrencyTypeId(), ctx.getPaymentCurrencyTypeId()),
						cptId)
				.orElse(null);

		UUID itemCategoryId = null;
		if (payload.getInvoiceId() != null) {
			itemCategoryId = glPostingDao.findDominantItemCategoryIdForInvoice(payload.getInvoiceId()).orElse(null);
		}

		Instant collectedAt = payload.getCollectedAt() != null ? payload.getCollectedAt() : ctx.getCollectedAt();
		LocalDate asOfDate = collectedAt.atZone(ZoneOffset.UTC).toLocalDate();

		GlMappingRuleRow rule = glPostingDao
				.findBestMappingRule(applicationId, asOfDate, paymentMethodTypeId, paymentCurrencyTypeId,
						itemCategoryId, properties.getSourceTypeCode(), properties.getTransactionTypeCode(),
						properties.getJournalTypeCode())
				.orElseThrow(() -> new IllegalStateException(
						"No active gl_mapping_rule for PAYMENT/PAYMENT_COLLECTION (applicationId=" + applicationId
								+ ")"));

		UUID unpostedStatusId = glPostingDao.findGlPostingStatusIdByCode("UNPOSTED")
				.orElseThrow(() -> new IllegalStateException("lu_gl_posting_status UNPOSTED not found"));
		UUID postedStatusId = glPostingDao.findGlPostingStatusIdByCode("POSTED")
				.orElseThrow(() -> new IllegalStateException("lu_gl_posting_status POSTED not found"));
		UUID entityTypeId = glPostingDao
				.findLookupIdByCode("reconciliation.lu_reconciliation_entity_type",
						"reconciliation_entity_type_id", "code", properties.getEntityTypeCode())
				.orElseThrow(() -> new IllegalStateException(
						"lu_reconciliation_entity_type " + properties.getEntityTypeCode() + " not found"));

		UUID financialPeriodId = glPostingDao.findFinancialPeriodId(asOfDate).orElse(null);
		UUID journalTypeId = coalesce(rule.getJournalTypeId(),
				glPostingDao
						.findLookupIdByCode("reconciliation.lu_journal_type", "journal_type_id", "code",
								properties.getJournalTypeCode())
						.orElse(null));

		glPostingDao.insertGlEntryPair(rule, financialPeriodId, payload.getLevelId(), entityTypeId, cptId.toString(),
				journalTypeId, rule.getLineOfBusinessId(), paymentCurrencyTypeId, amount, unpostedStatusId,
				postedStatusId, payload.getCreatedBy(), debitRef, creditRef);
	}

	private UUID resolveApplicationId(GlPaymentCollectedPayload payload) {
		UUID applicationId = payload.getApplicationId();
		if (applicationId == null && payload.getLevelId() != null) {
			applicationId = glPostingDao.findApplicationIdByLevelId(payload.getLevelId()).orElse(null);
		}
		if (applicationId == null) {
			applicationId = properties.getDefaultApplicationId();
		}
		if (applicationId == null) {
			throw new IllegalStateException("applicationId could not be resolved for GL posting");
		}
		return applicationId;
	}

	private static UUID coalesce(UUID primary, UUID fallback) {
		return primary != null ? primary : fallback;
	}
}
