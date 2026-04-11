package io.clubone.transaction.billing.quote;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.CollectionUtils;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.clubone.transaction.billing.quote.BillingQuoteDeserializeModels.AppliedPricingRow;
import io.clubone.transaction.billing.quote.BillingQuoteDeserializeModels.BillingSection;
import io.clubone.transaction.billing.quote.BillingQuoteDeserializeModels.EntitlementRow;
import io.clubone.transaction.billing.quote.BillingQuoteDeserializeModels.PackagePriceRow;
import io.clubone.transaction.billing.quote.BillingQuoteDeserializeModels.PlanPosDetailSection;
import io.clubone.transaction.billing.quote.BillingQuoteDeserializeModels.RecurringForecastRow;
import io.clubone.transaction.billing.quote.BillingQuoteDeserializeModels.QuoteLineItemRow;
import io.clubone.transaction.dao.SubscriptionPlanDao;
import io.clubone.transaction.dao.billing.BillingEnterpriseLookupDao;
import io.clubone.transaction.response.BillingQuoteLineItemsResponse;

/**
 * Persists billing quote /line-items API payloads into client_subscription_billing tables.
 */
@Service
public class BillingQuoteSubscriptionPersistenceService {

	private static final Logger log = LoggerFactory.getLogger(BillingQuoteSubscriptionPersistenceService.class);

	private final JdbcTemplate jdbc;
	private final ObjectMapper objectMapper;
	private final BillingEnterpriseLookupDao billingLookup;
	private final SubscriptionPlanDao subscriptionPlanDao;

	@Value("${clubone.billing.quote.schedule-status-code:PLANNED}")
	private String initialBillingScheduleStatusCode;

	@Value("${clubone.billing.quote.billing-success-status-code:PAID}")
	private String billingHistorySuccessStatusCode;

	/** When quote {@code billing.frequencyCode} is {@code PIF}, map to this {@code billing_period_unit.code} (e.g. MONTH). */
	@Value("${clubone.billing.quote.pif-billing-period-fallback-code:MONTH}")
	private String pifBillingPeriodFallbackCode;

	public BillingQuoteSubscriptionPersistenceService(@Qualifier("cluboneJdbcTemplate") JdbcTemplate jdbc,
			ObjectMapper objectMapper,
			BillingEnterpriseLookupDao billingLookup,
			SubscriptionPlanDao subscriptionPlanDao) {
		this.jdbc = jdbc;
		this.objectMapper = objectMapper;
		this.billingLookup = billingLookup;
		this.subscriptionPlanDao = subscriptionPlanDao;
	}

	/**
	 * Writes one subscription stack per quote response (aligned with each finalize spec line).
	 */
	@Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
	public void persistFromQuoteResponses(List<BillingQuoteLineItemsResponse> quotes, UUID transactionId,
			UUID clientAgreementId, UUID invoiceId, UUID clientPaymentTransactionId, UUID createdBy) {
		log.info(
				"[billing-quote/persist] step=start transactionId={} invoiceId={} clientAgreementId={} clientPaymentTransactionId={} quoteCount={} createdBy={}",
				transactionId, invoiceId, clientAgreementId, clientPaymentTransactionId,
				quotes == null ? 0 : quotes.size(), createdBy);
		if (CollectionUtils.isEmpty(quotes)) {
			log.info("[billing-quote/persist] step=validation outcome=skip reason=no_quotes");
			return;
		}
		if (clientAgreementId == null) {
			log.warn("[billing-quote/persist] step=validation outcome=skip reason=null_client_agreement invoiceId={}",
					invoiceId);
			return;
		}
		Optional<UUID> cpmOpt = subscriptionPlanDao.findClientPaymentMethodIdByTransactionId(transactionId);
		String cpmSource = "transaction_join";
		if (cpmOpt.isEmpty() && clientPaymentTransactionId != null) {
			/*
			 * finalize runs in an outer @Transactional; this method uses REQUIRES_NEW, so the
			 * transactions.transaction row may be uncommitted and invisible to the CPT join.
			 * client_payment_transaction is committed with the payment — resolve CPM directly.
			 */
			log.info(
					"[billing-quote/persist] step=resolve_cpm fallback=client_payment_transaction reason=txn_row_not_visible_or_missing transactionId={} clientPaymentTransactionId={}",
					transactionId, clientPaymentTransactionId);
			cpmOpt = subscriptionPlanDao.findClientPaymentMethodIdByClientPaymentTransactionId(clientPaymentTransactionId);
			cpmSource = "client_payment_transaction";
		}
		if (cpmOpt.isEmpty()) {
			log.warn(
					"[billing-quote/persist] step=resolve_cpm outcome=skip reason=not_found transactionId={} invoiceId={} clientPaymentTransactionId={}",
					transactionId, invoiceId, clientPaymentTransactionId);
			return;
		}
		UUID clientPaymentMethodId = cpmOpt.get();
		log.info("[billing-quote/persist] step=resolve_cpm outcome=ok source={} clientPaymentMethodId={}", cpmSource,
				clientPaymentMethodId);
		log.info(
				"[billing-quote/persist] step=resolve_status_codes billingStatusCode={} instanceStatusName=ACTIVE scheduleStatusCode={} pifFallbackPeriodCode={}",
				billingHistorySuccessStatusCode, initialBillingScheduleStatusCode, pifBillingPeriodFallbackCode);
		UUID billingStatusId = billingLookup.requireBillingStatusId(billingHistorySuccessStatusCode);
		UUID instanceStatusId = billingLookup.requireSubscriptionInstanceStatusIdByName("ACTIVE");
		UUID scheduleStatusId = billingLookup.requireBillingScheduleStatusId(initialBillingScheduleStatusCode);
		log.info(
				"[billing-quote/persist] step=resolve_status_ids outcome=ok billingStatusId={} instanceStatusId={} scheduleStatusId={}",
				billingStatusId, instanceStatusId, scheduleStatusId);

		int q = 0;
		for (BillingQuoteLineItemsResponse quote : quotes) {
			q++;
			log.info(
					"[billing-quote/persist] step=quote_iteration start quoteIndex={}/{} planTemplateId={} entityId={} startDate={}",
					q, quotes.size(), quote.getPlanTemplateId(), quote.getEntityId(), quote.getStartDate());
			persistOneQuote(quote, clientPaymentMethodId, clientAgreementId, invoiceId, clientPaymentTransactionId,
					createdBy, billingStatusId, instanceStatusId, scheduleStatusId);
			log.info("[billing-quote/persist] step=quote_iteration complete quoteIndex={}/{}", q, quotes.size());
		}
		log.info("[billing-quote/persist] step=complete outcome=ok transactionId={} invoiceId={} quotesProcessed={}",
				transactionId, invoiceId, quotes.size());
	}

	private void persistOneQuote(BillingQuoteLineItemsResponse quote, UUID clientPaymentMethodId,
			UUID clientAgreementId, UUID invoiceId, UUID clientPaymentTransactionId, UUID createdBy,
			UUID billingStatusId, UUID instanceStatusId, UUID scheduleStatusId) {

		BillingSection billing = readSection(quote.getBilling(), BillingSection.class);
		PlanPosDetailSection pos = readSection(quote.getPlanPosDetail(), PlanPosDetailSection.class);
		if (billing == null || pos == null) {
			throw new IllegalStateException("Quote response missing billing or planPosDetail");
		}

		List<QuoteLineItemRow> lines = readLineItems(quote);
		log.info(
				"[billing-quote/persist] step=parse_quote planTemplateId={} frequencyCode={} lineItemCount={} packagePriceCount={} entitlementCount={}",
				pos.getPackagePlanTemplateId(), billing.getFrequencyCode(), lines.size(),
				pos.getPackagePrices() == null ? 0 : pos.getPackagePrices().size(),
				pos.getEntitlements() == null ? 0 : pos.getEntitlements().size());

		log.info(
				"[billing-quote/persist] step=billing_lookups codes frequency={} chargeTrigger={} chargeEnd={} timing={} alignment={} proration={} termConfigId={}",
				billing.getFrequencyCode(), billing.getChargeTriggerTypeCode(), billing.getChargeEndConditionCode(),
				billing.getBillingTimingCode(), billing.getBillingAlignmentCode(), billing.getProrationStrategyCode(),
				pos.getPackagePlanTemplateTermConfigId());
		UUID billingPeriodUnitId = billingLookup.requireBillingPeriodUnitIdByCode(billing.getFrequencyCode(),
				pifBillingPeriodFallbackCode);
		UUID chargeTriggerId = billingLookup.requireChargeTriggerTypeId(billing.getChargeTriggerTypeCode());
		UUID chargeEndId = billingLookup.requireChargeEndConditionId(billing.getChargeEndConditionCode());
		UUID billingTimingId = billingLookup.requireBillingTimingId(billing.getBillingTimingCode());
		UUID billingAlignmentId = billingLookup.requireBillingAlignmentId(billing.getBillingAlignmentCode());
		UUID prorationStrategyId = billingLookup.requireProrationStrategyId(billing.getProrationStrategyCode());
		UUID dayRuleId = billingLookup.findSubscriptionBillingDayRuleIdByTermConfig(pos.getPackagePlanTemplateTermConfigId());
		log.info(
				"[billing-quote/persist] step=billing_lookup_ids billingPeriodUnitId={} chargeTriggerId={} chargeEndId={} billingTimingId={} billingAlignmentId={} prorationStrategyId={} dayRuleId={}",
				billingPeriodUnitId, chargeTriggerId, chargeEndId, billingTimingId, billingAlignmentId,
				prorationStrategyId, dayRuleId);

		LocalDate contractStart = nzDate(pos.getBillingStartDate(), quote.getStartDate());
		LocalDate contractEnd = nzDate(pos.getBillingEndDate(), contractStart);
		log.info("[billing-quote/persist] step=contract_dates contractStart={} contractEnd={} timezone={}",
				contractStart, contractEnd, quote.getTimezone());

		log.info("[billing-quote/persist] step=insert_subscription_plan clientAgreementId={} packagePlanTemplateId={}",
				clientAgreementId, pos.getPackagePlanTemplateId());
		UUID subscriptionPlanId = jdbc.queryForObject("""
				INSERT INTO client_subscription_billing.subscription_plan (
				    subscription_plan_id,
				    client_payment_method_id,
				    subscription_frequency_id,
				    interval_count,
				    subscription_billing_day_rule_id,
				    contract_start_date,
				    contract_end_date,
				    client_agreement_id,
				    package_item_id,
				    package_plan_template_id,
				    term_interval_count,
				    term_total_cycles,
				    is_active,
				    created_on,
				    created_by
				) VALUES (
				    gen_random_uuid(), ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, true, now(), ?
				) RETURNING subscription_plan_id
				""", UUID.class,
				clientPaymentMethodId,
				billingPeriodUnitId,
				nz(billing.getIntervalCount(), 1),
				dayRuleId,
				contractStart,
				contractEnd,
				clientAgreementId,
				pos.getPackageItemId(),
				pos.getPackagePlanTemplateId(),
				pos.getTermIntervalCount(),
				pos.getTermTotalCycles(),
				createdBy);
		log.info("[billing-quote/persist] step=insert_subscription_plan outcome=ok subscriptionPlanId={}",
				subscriptionPlanId);

		ZoneId zone = parseZone(quote.getTimezone());
		Instant triggerAt = contractStart != null
				? contractStart.atStartOfDay(zone).toInstant()
				: Instant.now();

		UUID configSnapshotId = jdbc.queryForObject("""
				INSERT INTO client_subscription_billing.subscription_billing_config_snapshot (
				    subscription_billing_config_snapshot_id,
				    subscription_plan_id,
				    client_agreement_id,
				    charge_trigger_type_id,
				    charge_end_condition_id,
				    billing_period_unit_id,
				    interval_count,
				    billing_timing_id,
				    subscription_billing_day_rule_id,
				    billing_alignment_id,
				    billing_day_of_month,
				    account_cycle_day,
				    proration_strategy_id,
				    proration_case_code,
				    trigger_event_at,
				    end_specific_date,
				    created_by
				) VALUES (
				    gen_random_uuid(), ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?
				) RETURNING subscription_billing_config_snapshot_id
				""", UUID.class,
				subscriptionPlanId,
				clientAgreementId,
				chargeTriggerId,
				chargeEndId,
				billingPeriodUnitId,
				nz(billing.getIntervalCount(), 1),
				billingTimingId,
				dayRuleId,
				billingAlignmentId,
				billing.getBillingDayOfMonth(),
				billing.getBillingDayOfMonth(),
				prorationStrategyId,
				billing.getProrationCase(),
				Timestamp.from(triggerAt),
				contractEnd,
				createdBy);
		log.info("[billing-quote/persist] step=insert_config_snapshot outcome=ok subscriptionBillingConfigSnapshotId={}",
				configSnapshotId);

		log.info("[billing-quote/persist] step=insert_purchase_snapshot planCode={} quoteMode={}", pos.getLuPlanCode(),
				billing.getQuoteMode());
		UUID purchaseSnapshotId = jdbc.queryForObject("""
				INSERT INTO client_subscription_billing.subscription_purchase_snapshot (
				    subscription_purchase_snapshot_id,
				    subscription_plan_id,
				    client_agreement_id,
				    subscription_billing_config_snapshot_id,
				    package_item_id,
				    package_version_id,
				    package_plan_template_id,
				    plan_code,
				    plan_name,
				    plan_description,
				    term_interval_count,
				    term_total_cycles,
				    quote_mode,
				    disclosure_auto_renewal,
				    disclosure_min_term,
				    captured_by
				) VALUES (
				    gen_random_uuid(), ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?
				) RETURNING subscription_purchase_snapshot_id
				""", UUID.class,
				subscriptionPlanId,
				clientAgreementId,
				configSnapshotId,
				pos.getPackageItemId(),
				pos.getPackageVersionId(),
				pos.getPackagePlanTemplateId(),
				trunc(pos.getLuPlanCode(), 50),
				trunc(pos.getLuPlanName(), 100),
				pos.getLuPlanDescription(),
				pos.getTermIntervalCount(),
				pos.getTermTotalCycles(),
				trunc(billing.getQuoteMode(), 20),
				firstNonBlankLineField(lines, QuoteLineItemRow::getDisclosureAutoRenewal),
				firstNonBlankLineField(lines, QuoteLineItemRow::getDisclosureMinTerm),
				createdBy);
		log.info("[billing-quote/persist] step=insert_purchase_snapshot outcome=ok subscriptionPurchaseSnapshotId={}",
				purchaseSnapshotId);

		jdbc.update("""
				UPDATE client_subscription_billing.subscription_plan
				   SET purchase_snapshot_id = ?
				 WHERE subscription_plan_id = ?
				""", purchaseSnapshotId, subscriptionPlanId);
		log.info("[billing-quote/persist] step=update_subscription_plan_purchase_snapshot subscriptionPlanId={}",
				subscriptionPlanId);

		insertPurchaseSnapshotPrices(purchaseSnapshotId, pos.getPackagePrices());
		insertPurchaseSnapshotEntitlements(purchaseSnapshotId, pos.getEntitlements());
		insertPurchaseSnapshotLegalRows(purchaseSnapshotId, lines);

		LocalDate instanceEnd = contractEnd;
		log.info("[billing-quote/persist] step=insert_subscription_instance");
		UUID subscriptionInstanceId = jdbc.queryForObject("""
				INSERT INTO client_subscription_billing.subscription_instance (
				    subscription_instance_id,
				    subscription_plan_id,
				    client_agreement_id,
				    start_date,
				    end_date,
				    billing_start_date,
				    billing_end_date,
				    timezone,
				    next_billing_date,
				    last_billed_on,
				    current_cycle_number,
				    subscription_instance_status_id,
				    account_cycle_day,
				    created_on,
				    created_by
				) VALUES (
				    gen_random_uuid(), ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, now(), ?
				) RETURNING subscription_instance_id
				""", UUID.class,
				subscriptionPlanId,
				clientAgreementId,
				contractStart,
				instanceEnd,
				contractStart,
				contractEnd,
				nzStr(quote.getTimezone(), "UTC"),
				contractStart,
				LocalDate.now(),
				1,
				instanceStatusId,
				billing.getBillingDayOfMonth(),
				createdBy);
		log.info("[billing-quote/persist] step=insert_subscription_instance outcome=ok subscriptionInstanceId={}",
				subscriptionInstanceId);

		insertBillingForecastsFromRecurring(quote, subscriptionInstanceId, contractStart, contractEnd);

		List<AppliedPricingRow> applied = readAppliedPricing(quote);
		log.info("[billing-quote/persist] step=read_applied_pricing count={}", applied.size());

		ScheduleAgg agg = aggregateSchedule(lines);
		log.info(
				"[billing-quote/persist] step=aggregate_schedule cycle=1 label={} periodStart={} periodEnd={} billingDate={} baseAmount={} taxAmount={}",
				agg.label(), agg.periodStart(), agg.periodEnd(), agg.billingDate(), agg.baseAmount(), agg.taxAmount());

		log.info("[billing-quote/persist] step=insert_billing_schedule cycleNumber=1");
		UUID billingScheduleId = jdbc.queryForObject("""
				INSERT INTO client_subscription_billing.subscription_billing_schedule (
				    billing_schedule_id,
				    subscription_instance_id,
				    subscription_plan_id,
				    client_agreement_id,
				    cycle_number,
				    label,
				    period_label,
				    billing_period_start,
				    billing_period_end,
				    billing_date,
				    quantity,
				    unit_price,
				    unit_price_before_discount,
				    base_amount,
				    discount_amount,
				    tax_amount,
				    tax_pct,
				    subtotal_before_tax,
				    is_prorated,
				    is_one_time,
				    is_final_cycle,
				    billing_schedule_status_id,
				    proration_case_code,
				    proration_strategy_code,
				    created_on
				) VALUES (
				    gen_random_uuid(), ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, now()
				) RETURNING billing_schedule_id
				""", UUID.class,
				subscriptionInstanceId,
				subscriptionPlanId,
				clientAgreementId,
				1,
				agg.label(),
				agg.periodLabel(),
				agg.periodStart(),
				agg.periodEnd(),
				agg.billingDate(),
				agg.quantity(),
				agg.unitPrice(),
				agg.unitPriceBeforeDiscount(),
				agg.baseAmount(),
				BigDecimal.ZERO,
				agg.taxAmount(),
				agg.taxPct(),
				agg.subtotalBeforeTax(),
				Boolean.FALSE,
				isPaidInFull(billing.getFrequencyCode()),
				Boolean.TRUE,
				scheduleStatusId,
				billing.getProrationCase(),
				billing.getProrationStrategyCode());
		log.info("[billing-quote/persist] step=insert_billing_schedule outcome=ok billingScheduleId={}",
				billingScheduleId);

		log.info("[billing-quote/persist] step=insert_schedule_line_items count={}", lines.size());
		for (QuoteLineItemRow li : lines) {
			jdbc.update("""
					INSERT INTO client_subscription_billing.subscription_billing_schedule_line_item (
					    billing_schedule_line_item_id,
					    billing_schedule_id,
					    label,
					    start_date,
					    end_date,
					    quantity,
					    unit_price,
					    price,
					    tax_pct,
					    tax_amount,
					    prorated_amount,
					    is_prorated,
					    prorated_from_date,
					    prorated_to_date,
					    proration_type
					) VALUES (
					    gen_random_uuid(), ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?
					)
					""",
					billingScheduleId,
					trunc(li.getLabel(), 100),
					nzDate(li.getStartDate(), contractStart),
					nzDate(li.getEndDate(), contractEnd),
					nz(li.getQuantity(), 1),
					li.getUnitPrice(),
					li.getPrice(),
					li.getTaxPct(),
					li.getTax(),
					BigDecimal.ZERO,
					Boolean.TRUE.equals(li.getIsProrated()),
					null,
					null,
					li.getProratedChargeTiming());
		}
		log.info("[billing-quote/persist] step=insert_schedule_line_items outcome=ok");

		log.info("[billing-quote/persist] step=insert_applied_pricing count={}", applied.size());
		for (AppliedPricingRow ap : applied) {
			jdbc.update("""
					INSERT INTO client_subscription_billing.subscription_billing_applied_pricing (
					    applied_pricing_id,
					    billing_schedule_id,
					    unit_price_source,
					    price_cycle_band_id,
					    cycle_number,
					    unit_price,
					    discounted_unit_price,
					    entitlement_quantity_used,
					    pos_min_price,
					    pos_max_price,
					    created_on
					) VALUES (
					    gen_random_uuid(), ?, ?, ?, ?, ?, ?, ?, ?, ?, now()
					)
					""",
					billingScheduleId,
					trunc(ap.getUnitPriceSource(), 50),
					ap.getPriceCycleBandId(),
					ap.getCycleNumber(),
					ap.getUnitPrice(),
					ap.getDiscountedUnitPrice(),
					ap.getEntitlementQuantityUsed(),
					ap.getPosPriceMin(),
					ap.getPosPriceMax());
		}
		log.info("[billing-quote/persist] step=insert_applied_pricing outcome=ok");

		insertSnapshotCyclePricesFromApplied(purchaseSnapshotId, applied);
		log.info("[billing-quote/persist] step=insert_snapshot_cycle_prices_from_applied done");

		insertScheduleAudit(billingScheduleId, createdBy);
		log.info("[billing-quote/persist] step=insert_schedule_audit outcome=ok billingScheduleId={}",
				billingScheduleId);

		if (invoiceId != null) {
			BigDecimal invoiceTotal = null;
			try {
				invoiceTotal = jdbc.queryForObject(
						"SELECT total_amount FROM transactions.invoice WHERE invoice_id = ?",
						BigDecimal.class, invoiceId);
			} catch (EmptyResultDataAccessException ignored) {
				// leave null
			}
			log.info(
					"[billing-quote/persist] step=insert_billing_history invoiceId={} invoiceTotalAmount={} billingStatusId={}",
					invoiceId, invoiceTotal, billingStatusId);
			jdbc.update("""
					INSERT INTO client_subscription_billing.subscription_billing_history (
					    subscription_billing_history_id,
					    subscription_instance_id,
					    billing_attempt_on,
					    client_payment_transaction_id,
					    billing_status_id,
					    invoice_id,
					    is_mock,
					    invoice_total_amount,
					    created_on
					) VALUES (
					    gen_random_uuid(), ?, now(), ?, ?, ?, false, ?, now()
					)
					""",
					subscriptionInstanceId,
					clientPaymentTransactionId,
					billingStatusId,
					invoiceId,
					invoiceTotal);
			log.info("[billing-quote/persist] step=insert_billing_history outcome=ok");
		} else {
			log.info("[billing-quote/persist] step=insert_billing_history skipped=true reason=null_invoice_id");
		}

		log.info(
				"[billing-quote/persist] step=stack_complete subscriptionPlanId={} subscriptionInstanceId={} billingScheduleId={} purchaseSnapshotId={} configSnapshotId={}",
				subscriptionPlanId, subscriptionInstanceId, billingScheduleId, purchaseSnapshotId, configSnapshotId);
	}

	private void insertScheduleAudit(UUID billingScheduleId, UUID createdBy) {
		try {
			ObjectNode node = objectMapper.createObjectNode();
			node.put("billingScheduleId", billingScheduleId.toString());
			String json = objectMapper.writeValueAsString(node);
			jdbc.update("""
					INSERT INTO client_subscription_billing.subscription_billing_schedule_audit (
					    billing_schedule_audit_id,
					    billing_schedule_id,
					    action_code,
					    old_data,
					    new_data,
					    created_by
					) VALUES (gen_random_uuid(), ?, 'INSERT', NULL, ?::jsonb, ?)
					""", billingScheduleId, json, createdBy);
		} catch (JsonProcessingException e) {
			throw new IllegalStateException(e);
		}
	}

	private void insertPurchaseSnapshotPrices(UUID purchaseSnapshotId, List<PackagePriceRow> rows) {
		if (rows == null) {
			log.info("[billing-quote/persist] step=insert_purchase_snapshot_prices skipped=true reason=null_rows");
			return;
		}
		int inserted = 0;
		log.info("[billing-quote/persist] step=insert_purchase_snapshot_prices start purchaseSnapshotId={} rowCount={}",
				purchaseSnapshotId, rows.size());
		for (PackagePriceRow r : rows) {
			if (r == null || r.getPrice() == null) {
				continue;
			}
			jdbc.update("""
					INSERT INTO client_subscription_billing.subscription_purchase_snapshot_price (
					    purchase_snapshot_price_id,
					    subscription_purchase_snapshot_id,
					    package_price_id,
					    package_location_id,
					    location_level_id,
					    price,
					    min_price,
					    max_price,
					    created_on
					) VALUES (gen_random_uuid(), ?, ?, ?, ?, ?, ?, ?, now())
					""",
					purchaseSnapshotId,
					r.getPackagePriceId(),
					r.getPackageLocationId(),
					r.getLocationLevelId(),
					r.getPrice(),
					r.getMinPrice(),
					r.getMaxPrice());
			inserted++;
		}
		log.info("[billing-quote/persist] step=insert_purchase_snapshot_prices outcome=ok rowsInserted={}", inserted);
	}

	private void insertPurchaseSnapshotLegalRows(UUID purchaseSnapshotId, List<QuoteLineItemRow> lines) {
		if (purchaseSnapshotId == null || lines == null) {
			log.info("[billing-quote/persist] step=insert_purchase_snapshot_legal skipped=true reason=null_input");
			return;
		}
		int legalRows = 0;
		for (QuoteLineItemRow li : lines) {
			if (li == null) {
				continue;
			}
			if (li.getDisclosureAutoRenewal() != null && !li.getDisclosureAutoRenewal().isBlank()) {
				jdbc.update("""
						INSERT INTO client_subscription_billing.subscription_purchase_snapshot_legal (
						    purchase_snapshot_legal_id,
						    subscription_purchase_snapshot_id,
						    legal_type,
						    legal_text,
						    language_code,
						    created_on
						) VALUES (gen_random_uuid(), ?, ?, ?, 'en', now())
						""",
						purchaseSnapshotId,
						"AUTO_RENEWAL_DISCLOSURE",
						li.getDisclosureAutoRenewal().trim());
				legalRows++;
			}
			if (li.getDisclosureMinTerm() != null && !li.getDisclosureMinTerm().isBlank()) {
				jdbc.update("""
						INSERT INTO client_subscription_billing.subscription_purchase_snapshot_legal (
						    purchase_snapshot_legal_id,
						    subscription_purchase_snapshot_id,
						    legal_type,
						    legal_text,
						    language_code,
						    created_on
						) VALUES (gen_random_uuid(), ?, ?, ?, 'en', now())
						""",
						purchaseSnapshotId,
						"MIN_TERM_DISCLOSURE",
						li.getDisclosureMinTerm().trim());
				legalRows++;
			}
		}
		log.info("[billing-quote/persist] step=insert_purchase_snapshot_legal outcome=ok legalRowsInserted={}",
				legalRows);
	}

	private void insertBillingForecastsFromRecurring(BillingQuoteLineItemsResponse quote, UUID subscriptionInstanceId,
			LocalDate contractStart, LocalDate contractEnd) {
		JsonNode recurring = quote.getRecurring();
		if (recurring == null || recurring.isNull() || !recurring.isArray() || recurring.isEmpty()) {
			log.info(
					"[billing-quote/persist] step=insert_billing_forecast skipped=true reason=no_recurring_array subscriptionInstanceId={}",
					subscriptionInstanceId);
			return;
		}
		int fc = 0;
		log.info(
				"[billing-quote/persist] step=insert_billing_forecast start subscriptionInstanceId={} recurringElementCount={}",
				subscriptionInstanceId, recurring.size());
		for (JsonNode n : recurring) {
			if (n == null || n.isNull()) {
				continue;
			}
			RecurringForecastRow r = objectMapper.convertValue(n, RecurringForecastRow.class);
			LocalDate pStart = r.resolvedPeriodStart() != null ? r.resolvedPeriodStart() : contractStart;
			LocalDate pEnd = r.resolvedPeriodEnd() != null ? r.resolvedPeriodEnd() : contractEnd;
			LocalDate bDate = r.resolvedBillingDate() != null ? r.resolvedBillingDate() : pStart;
			jdbc.update("""
					INSERT INTO client_subscription_billing.subscription_billing_forecast (
					    billing_forecast_id,
					    subscription_instance_id,
					    cycle_number,
					    period_start,
					    period_end,
					    billing_date,
					    unit_price,
					    tax_pct,
					    tax_amount,
					    amount,
					    discounted_amount,
					    period_label,
					    created_on
					) VALUES (gen_random_uuid(), ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, now())
					""",
					subscriptionInstanceId,
					r.resolvedCycleNumber(),
					pStart,
					pEnd,
					bDate,
					r.resolvedUnitPrice(),
					r.resolvedTaxPct(),
					r.resolvedTaxAmount(),
					r.getAmount(),
					r.resolvedDiscountedAmount(),
					trunc(r.resolvedPeriodLabel(), 100));
			fc++;
		}
		log.info("[billing-quote/persist] step=insert_billing_forecast outcome=ok subscriptionInstanceId={} rowsInserted={}",
				subscriptionInstanceId, fc);
	}

	private void insertPurchaseSnapshotEntitlements(UUID purchaseSnapshotId, List<EntitlementRow> rows) {
		if (rows == null) {
			log.info("[billing-quote/persist] step=insert_purchase_snapshot_entitlements skipped=true reason=null_rows");
			return;
		}
		int ent = 0;
		log.info("[billing-quote/persist] step=insert_purchase_snapshot_entitlements start rowCount={}", rows.size());
		for (EntitlementRow r : rows) {
			if (r == null || r.getEntitlementModeId() == null) {
				continue;
			}
			jdbc.update("""
					INSERT INTO client_subscription_billing.subscription_purchase_snapshot_entitlement (
					    purchase_snapshot_entitlement_id,
					    subscription_purchase_snapshot_id,
					    entitlement_mode_id,
					    quantity_per_cycle,
					    total_entitlement,
					    is_unlimited,
					    max_redemptions_per_day
					) VALUES (gen_random_uuid(), ?, ?, ?, ?, ?, ?)
					""",
					purchaseSnapshotId,
					r.getEntitlementModeId(),
					r.getQuantityPerCycle(),
					r.getTotalEntitlement(),
					Boolean.TRUE.equals(r.getIsUnlimited()),
					r.getMaxRedemptionsPerDay());
			ent++;
		}
		log.info("[billing-quote/persist] step=insert_purchase_snapshot_entitlements outcome=ok rowsInserted={}", ent);
	}

	private void insertSnapshotCyclePricesFromApplied(UUID purchaseSnapshotId, List<AppliedPricingRow> applied) {
		if (purchaseSnapshotId == null || applied == null) {
			log.info("[billing-quote/persist] step=insert_snapshot_cycle_prices skipped=true reason=null_input");
			return;
		}
		int c = 0;
		log.info("[billing-quote/persist] step=insert_snapshot_cycle_prices start appliedPricingCount={}",
				applied.size());
		for (AppliedPricingRow ap : applied) {
			if (ap == null || ap.getUnitPrice() == null) {
				continue;
			}
			jdbc.update("""
					INSERT INTO client_subscription_billing.subscription_purchase_snapshot_cycle_price (
					    purchase_snapshot_cycle_price_id,
					    subscription_purchase_snapshot_id,
					    cycle_start,
					    cycle_end,
					    unit_price,
					    price_cycle_band_id,
					    allow_pos_price_override,
					    created_on
					) VALUES (gen_random_uuid(), ?, ?, ?, ?, ?, false, now())
					""",
					purchaseSnapshotId,
					ap.getCycleNumber() != null ? ap.getCycleNumber() : 1,
					null,
					ap.getUnitPrice(),
					ap.getPriceCycleBandId());
			c++;
		}
		log.info("[billing-quote/persist] step=insert_snapshot_cycle_prices outcome=ok rowsInserted={}", c);
	}

	private <T> T readSection(com.fasterxml.jackson.databind.JsonNode node, Class<T> type) {
		if (node == null || node.isNull()) {
			return null;
		}
		return objectMapper.convertValue(node, type);
	}

	private List<QuoteLineItemRow> readLineItems(BillingQuoteLineItemsResponse quote) {
		if (quote.getLineItems() == null || quote.getLineItems().isNull()) {
			return List.of();
		}
		return objectMapper.convertValue(quote.getLineItems(), new TypeReference<List<QuoteLineItemRow>>() {
		});
	}

	private List<AppliedPricingRow> readAppliedPricing(BillingQuoteLineItemsResponse quote) {
		if (quote.getAppliedPricing() == null || quote.getAppliedPricing().isNull()) {
			return List.of();
		}
		return objectMapper.convertValue(quote.getAppliedPricing(), new TypeReference<List<AppliedPricingRow>>() {
		});
	}

	private static String firstNonBlankLineField(List<QuoteLineItemRow> lines,
			java.util.function.Function<QuoteLineItemRow, String> getter) {
		if (lines == null) {
			return null;
		}
		for (QuoteLineItemRow li : lines) {
			if (li == null) {
				continue;
			}
			String s = getter.apply(li);
			if (s != null && !s.isBlank()) {
				return s.trim();
			}
		}
		return null;
	}

	private record ScheduleAgg(String label, String periodLabel, LocalDate periodStart, LocalDate periodEnd,
			LocalDate billingDate, int quantity, BigDecimal unitPrice, BigDecimal unitPriceBeforeDiscount,
			BigDecimal baseAmount, BigDecimal taxAmount, BigDecimal taxPct, BigDecimal subtotalBeforeTax) {
	}

	private ScheduleAgg aggregateSchedule(List<QuoteLineItemRow> lines) {
		if (lines.isEmpty()) {
			LocalDate today = LocalDate.now();
			return new ScheduleAgg("Billing", null, today, today, today, 1, BigDecimal.ZERO, BigDecimal.ZERO,
					BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);
		}
		BigDecimal subtotal = BigDecimal.ZERO;
		BigDecimal tax = BigDecimal.ZERO;
		int qty = 0;
		LocalDate pStart = null;
		LocalDate pEnd = null;
		LocalDate billDate = null;
		for (QuoteLineItemRow li : lines) {
			if (li.getPrice() != null) {
				subtotal = subtotal.add(li.getPrice());
			}
			if (li.getTax() != null) {
				tax = tax.add(li.getTax());
			}
			qty += nz(li.getQuantity(), 1);
			if (li.getStartDate() != null) {
				pStart = pStart == null ? li.getStartDate() : li.getStartDate().isBefore(pStart) ? li.getStartDate() : pStart;
			}
			if (li.getEndDate() != null) {
				pEnd = pEnd == null ? li.getEndDate() : li.getEndDate().isAfter(pEnd) ? li.getEndDate() : pEnd;
			}
			if (li.getBillingDate() != null) {
				billDate = li.getBillingDate();
			}
		}
		QuoteLineItemRow first = lines.get(0);
		if (pStart == null) {
			pStart = nzDate(first.getStartDate(), LocalDate.now());
		}
		if (pEnd == null) {
			pEnd = nzDate(first.getEndDate(), pStart);
		}
		if (billDate == null) {
			billDate = nzDate(first.getBillingDate(), pStart);
		}
		BigDecimal avgUnit = qty > 0
				? subtotal.divide(BigDecimal.valueOf(qty), 2, RoundingMode.HALF_UP)
				: subtotal;
		BigDecimal taxPct = first.getTaxPct();
		String label = first.getLabel() != null ? first.getLabel() : "Billing";
		return new ScheduleAgg(label, null, pStart, pEnd, billDate, qty, avgUnit, first.getUnitPriceBeforeDiscount(),
				subtotal, tax, taxPct, subtotal);
	}

	private static boolean isPaidInFull(String frequencyCode) {
		return frequencyCode != null && "PIF".equalsIgnoreCase(frequencyCode.trim());
	}

	private static LocalDate nzDate(LocalDate a, LocalDate b) {
		return a != null ? a : b;
	}

	private static int nz(Integer v, int d) {
		return v != null ? v : d;
	}

	private static String nzStr(String s, String d) {
		return s != null && !s.isBlank() ? s : d;
	}

	private static String trunc(String s, int max) {
		if (s == null) {
			return null;
		}
		return s.length() <= max ? s : s.substring(0, max);
	}

	private static ZoneId parseZone(String tz) {
		if (tz == null || tz.isBlank()) {
			return ZoneId.of("UTC");
		}
		try {
			return ZoneId.of(tz.trim());
		} catch (Exception e) {
			return ZoneId.of("UTC");
		}
	}
}
