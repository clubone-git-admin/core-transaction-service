package io.clubone.transaction.billing.quote;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Locale;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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

import io.clubone.transaction.billing.quote.BillingQuoteDeserializeModels.AppliedPricingRow;
import io.clubone.transaction.billing.quote.BillingQuoteDeserializeModels.BillingSection;
import io.clubone.transaction.billing.quote.BillingQuoteDeserializeModels.EntitlementRow;
import io.clubone.transaction.billing.quote.BillingQuoteDeserializeModels.PackagePriceRow;
import io.clubone.transaction.billing.quote.BillingQuoteDeserializeModels.PlanPosDetailSection;
import io.clubone.transaction.billing.quote.BillingQuoteDeserializeModels.RecurringForecastRow;
import io.clubone.transaction.billing.quote.BillingQuoteDeserializeModels.QuoteLineItemRow;
import io.clubone.transaction.billing.quote.BillingQuoteDeserializeModels.QuotePromotionRow;
import io.clubone.transaction.dao.SubscriptionPlanDao;
import io.clubone.transaction.dao.billing.BillingEnterpriseLookupDao;
import io.clubone.transaction.dao.billing.PurchaseSnapshotLookupDao;
import io.clubone.transaction.response.BillingQuoteLineItemsResponse;

/**
 * Persists billing quote /line-items API payloads into client_subscription_billing tables.
 */
@Service
public class BillingQuoteSubscriptionPersistenceService {

	private static final Logger log = LoggerFactory.getLogger(BillingQuoteSubscriptionPersistenceService.class);

	private static final Pattern CYCLE_LABEL_NUMBER = Pattern.compile("(?i)cycle\\s*#?\\s*(\\d+)");

	/** First two ISO dates in {@code period_label} (e.g. human text with embedded yyyy-MM-dd). */
	private static final Pattern PERIOD_LABEL_ISO_DATES = Pattern.compile("(\\d{4}-\\d{2}-\\d{2})");

	/** Text inside parentheses, e.g. {@code Cycle 2 (May 23-Jun 22)} → {@code May 23-Jun 22}. */
	private static final Pattern PERIOD_LABEL_PAREN_CONTENT = Pattern.compile("\\(([^)]+)\\)");

	private static final DateTimeFormatter MONTH_DAY_EN = DateTimeFormatter.ofPattern("MMM d uuuu", Locale.ENGLISH);

	private record PendingSchedule(boolean fromAgg, RecurringForecastRow recRow, int recurringIndex, int cycleNumber) {
	}

	private record ForecastBounds(LocalDate periodStart, LocalDate periodEnd) {
	}

	private record InvoiceHeaderAmounts(BigDecimal totalAmount, BigDecimal subTotal, BigDecimal taxAmount,
			BigDecimal discountAmount) {
		static InvoiceHeaderAmounts empty() {
			return new InvoiceHeaderAmounts(null, null, null, null);
		}
	}

	private final JdbcTemplate jdbc;
	private final ObjectMapper objectMapper;
	private final BillingEnterpriseLookupDao billingLookup;
	private final PurchaseSnapshotLookupDao purchaseSnapshotLookupDao;
	private final SubscriptionPlanDao subscriptionPlanDao;

	@Value("${clubone.billing.quote.schedule-status-code:PLANNED}")
	private String initialBillingScheduleStatusCode;

	@Value("${clubone.billing.quote.billing-success-status-code:PAID}")
	private String billingHistorySuccessStatusCode;

	/** When quote {@code billing.frequencyCode} is {@code PIF}, map to this {@code billing_period_unit.code} (e.g. MONTH). */
	@Value("${clubone.billing.quote.pif-billing-period-fallback-code:MONTH}")
	private String pifBillingPeriodFallbackCode;

	@Value("${clubone.billing.quote.purchase-snapshot-kind-finalize-code:FINALIZE_CHECKOUT}")
	private String purchaseSnapshotKindFinalizeCode;

	@Value("${clubone.billing.quote.purchase-snapshot-line-type-fee-code:FEE}")
	private String purchaseSnapshotLineTypeFeeCode;

	@Value("${clubone.billing.quote.purchase-snapshot-line-type-subscription-code:SUBSCRIPTION}")
	private String purchaseSnapshotLineTypeSubscriptionCode;

	@Value("${clubone.billing.quote.purchase-snapshot-legal-auto-renewal-code:AUTO_RENEWAL_DISCLOSURE}")
	private String purchaseSnapshotLegalAutoRenewalCode;

	@Value("${clubone.billing.quote.purchase-snapshot-legal-min-term-code:MIN_TERM_DISCLOSURE}")
	private String purchaseSnapshotLegalMinTermCode;

	@Value("${clubone.billing.quote.purchase-snapshot-legal-default-language-code:en}")
	private String purchaseSnapshotLegalDefaultLanguageCode;

	@Value("${clubone.billing.quote.fee-item-group-codes:FEE}")
	private String feeItemGroupCodes;

	@Value("${clubone.billing.quote.purchase-snapshot-tax-fallback-name:Tax}")
	private String purchaseSnapshotTaxFallbackName;

	@Value("${clubone.billing.quote.purchase-snapshot-promo-default-cycle-start:1}")
	private int purchaseSnapshotPromoDefaultCycleStart;

	public BillingQuoteSubscriptionPersistenceService(@Qualifier("cluboneJdbcTemplate") JdbcTemplate jdbc,
			ObjectMapper objectMapper,
			BillingEnterpriseLookupDao billingLookup,
			PurchaseSnapshotLookupDao purchaseSnapshotLookupDao,
			SubscriptionPlanDao subscriptionPlanDao) {
		this.jdbc = jdbc;
		this.objectMapper = objectMapper;
		this.billingLookup = billingLookup;
		this.purchaseSnapshotLookupDao = purchaseSnapshotLookupDao;
		this.subscriptionPlanDao = subscriptionPlanDao;
	}

	/**
	 * Writes one subscription stack per quote response (aligned with each finalize spec line).
	 */
	@Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
	public void persistFromQuoteResponses(List<BillingQuoteLineItemsResponse> quotes, UUID transactionId,
			UUID clientAgreementId, UUID invoiceId, UUID clientPaymentTransactionId, UUID createdBy,
			boolean invoicePaid) {
		log.info(
				"[billing-quote/persist] step=start transactionId={} invoiceId={} clientAgreementId={} clientPaymentTransactionId={} quoteCount={} invoicePaid={} createdBy={}",
				transactionId, invoiceId, clientAgreementId, clientPaymentTransactionId,
				quotes == null ? 0 : quotes.size(), invoicePaid, createdBy);
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
		UUID plannedScheduleStatusId = billingLookup.requireBillingScheduleStatusId(initialBillingScheduleStatusCode);
		UUID paidScheduleStatusId = billingLookup.requireBillingScheduleStatusId("PAID");
		log.info(
				"[billing-quote/persist] step=resolve_status_ids outcome=ok billingStatusId={} instanceStatusId={} plannedScheduleStatusId={} paidScheduleStatusId={}",
				billingStatusId, instanceStatusId, plannedScheduleStatusId, paidScheduleStatusId);

		int q = 0;
		for (BillingQuoteLineItemsResponse quote : quotes) {
			q++;
			log.info(
					"[billing-quote/persist] step=quote_iteration start quoteIndex={}/{} planTemplateId={} entityId={} startDate={}",
					q, quotes.size(), quote.getPlanTemplateId(), quote.getEntityId(), quote.getStartDate());
			persistOneQuote(quote, clientPaymentMethodId, clientAgreementId, invoiceId, clientPaymentTransactionId,
					transactionId, createdBy, billingStatusId, instanceStatusId, plannedScheduleStatusId,
					paidScheduleStatusId, invoicePaid);
			log.info("[billing-quote/persist] step=quote_iteration complete quoteIndex={}/{}", q, quotes.size());
		}
		log.info("[billing-quote/persist] step=complete outcome=ok transactionId={} invoiceId={} quotesProcessed={}",
				transactionId, invoiceId, quotes.size());
	}

	private void persistOneQuote(BillingQuoteLineItemsResponse quote, UUID clientPaymentMethodId,
			UUID clientAgreementId, UUID invoiceId, UUID clientPaymentTransactionId, UUID transactionId, UUID createdBy,
			UUID billingStatusId, UUID instanceStatusId, UUID plannedScheduleStatusId, UUID paidScheduleStatusId,
			boolean invoicePaid) {

		BillingSection billing = readSection(quote.getBilling(), BillingSection.class);
		PlanPosDetailSection pos = readSection(quote.getPlanPosDetail(), PlanPosDetailSection.class);
		if (billing == null || pos == null) {
			throw new IllegalStateException("Quote response missing billing or planPosDetail");
		}

		List<QuoteLineItemRow> lines = readLineItems(quote);
		List<QuoteLineItemRow> subscriptionLines = nonFeeLineItems(lines);
		int feeLineCount = lines.size() - subscriptionLines.size();
		log.info(
				"[billing-quote/persist] step=parse_quote planTemplateId={} frequencyCode={} lineItemCount={} subscriptionLineItemCount={} feeLineItemsSkipped={} packagePriceCount={} entitlementCount={}",
				pos.getPackagePlanTemplateId(), billing.getFrequencyCode(), lines.size(), subscriptionLines.size(),
				feeLineCount, pos.getPackagePrices() == null ? 0 : pos.getPackagePrices().size(),
				pos.getEntitlements() == null ? 0 : pos.getEntitlements().size());

		/*
		 * Fee-only quotes (e.g. PIF upfront fee line with itemGroupCode FEE) must not create subscription_* rows.
		 * Previously we only omitted FEE lines from line-item inserts but still inserted plan/snapshot/instance/schedule.
		 */
		if (!lines.isEmpty() && subscriptionLines.isEmpty()) {
			log.info(
					"[billing-quote/persist] step=fee_only_snapshot planTemplateId={} packagePlanTemplateId={} lineCount={}",
					quote.getPlanTemplateId(), pos.getPackagePlanTemplateId(), lines.size());
			persistFeeOnlyQuoteSnapshot(quote, billing, pos, lines, clientPaymentMethodId, clientAgreementId, invoiceId,
					clientPaymentTransactionId, transactionId, createdBy);
			return;
		}

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
				    client_agreement_id,
				    package_item_id,
				    package_plan_template_id,
				    term_total_cycles,
				    is_active,
				    created_on,
				    created_by
				) VALUES (
				    gen_random_uuid(), ?, ?, ?, ?, ?, true, now(), ?
				) RETURNING subscription_plan_id
				""", UUID.class,
				clientPaymentMethodId,
				clientAgreementId,
				pos.getPackageItemId(),
				pos.getPackagePlanTemplateId(),
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
				    gen_random_uuid(), ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?
				) RETURNING subscription_billing_config_snapshot_id
				""", UUID.class,
				subscriptionPlanId,
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

		log.info("[billing-quote/persist] step=insert_purchase_snapshot planCode={}", pos.getLuPlanCode());
		UUID purchaseSnapshotKindId = purchaseSnapshotLookupDao
				.requirePurchaseSnapshotKindId(purchaseSnapshotKindFinalizeCode);
		String presentationJson = quotePresentationJson(quote);
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
				    invoice_id,
				    client_payment_transaction_id,
				    transaction_id,
				    purchase_snapshot_kind_id,
				    presentation_json,
				    captured_by
				) VALUES (
				    gen_random_uuid(), ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CAST(? AS jsonb), ?
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
				invoiceId,
				clientPaymentTransactionId,
				transactionId,
				purchaseSnapshotKindId,
				presentationJson,
				createdBy);
		log.info("[billing-quote/persist] step=insert_purchase_snapshot outcome=ok subscriptionPurchaseSnapshotId={}",
				purchaseSnapshotId);

		insertPurchaseSnapshotPrices(purchaseSnapshotId, pos.getPackagePrices());
		insertPurchaseSnapshotEntitlements(purchaseSnapshotId, pos.getEntitlements());
		insertPurchaseSnapshotLegalRows(purchaseSnapshotId, subscriptionLines);
		insertPurchaseSnapshotTaxRows(purchaseSnapshotId, subscriptionLines);

		ZoneId quoteZone = parseZone(quote.getTimezone());
		LocalDate todayInQuoteTz = LocalDate.now(quoteZone);
		LocalDate nextBillingDate = resolveFirstCycleBillingDate(quote, subscriptionLines, contractStart);
		LocalDate lastBilledOn = resolveLastBilledOn(subscriptionLines, invoicePaid, todayInQuoteTz);
		log.info(
				"[billing-quote/persist] step=subscription_instance_dates nextBillingDate={} lastBilledOn={} invoicePaid={} todayInQuoteTz={}",
				nextBillingDate, lastBilledOn, invoicePaid, todayInQuoteTz);

		log.info("[billing-quote/persist] step=insert_subscription_instance");
		UUID subscriptionInstanceId = jdbc.queryForObject("""
				INSERT INTO client_subscription_billing.subscription_instance (
				    subscription_instance_id,
				    subscription_plan_id,
				    billing_start_date,
				    billing_end_date,
				    timezone,
				    next_billing_date,
				    last_billed_on,
				    current_cycle_number,
				    subscription_instance_status_id,
				    created_on,
				    created_by
				) VALUES (
				    gen_random_uuid(), ?, ?, ?, ?, ?, ?, ?, ?, now(), ?
				) RETURNING subscription_instance_id
				""", UUID.class,
				subscriptionPlanId,
				contractStart,
				contractEnd,
				nzStr(quote.getTimezone(), "UTC"),
				nextBillingDate,
				lastBilledOn,
				1,
				instanceStatusId,
				createdBy);
		log.info("[billing-quote/persist] step=insert_subscription_instance outcome=ok subscriptionInstanceId={}",
				subscriptionInstanceId);

		Map<Integer, UUID> snapshotLineIdsBySequence = insertPurchaseSnapshotLines(purchaseSnapshotId, lines,
				subscriptionPlanId, subscriptionInstanceId, pos);
		insertPurchaseSnapshotPromosFromQuoteRoot(purchaseSnapshotId, quote, snapshotLineIdsBySequence);

		List<RecurringForecastRow> recurringRows = readRecurringForecastRows(quote);

		List<AppliedPricingRow> applied = readAppliedPricing(quote);
		log.info("[billing-quote/persist] step=read_applied_pricing_for_snapshot_cycle_prices count={}",
				applied.size());

		ScheduleAgg agg = aggregateSchedule(subscriptionLines, contractStart, contractEnd);
		boolean anyLineProrated = subscriptionLines.stream()
				.anyMatch(li -> li != null && Boolean.TRUE.equals(li.getIsProrated()));
		boolean aggregateChargeBelowFullCycle = aggregateChargeLessThanFullCycleUnitTotal(subscriptionLines);
		boolean firstCycleProrated = anyLineProrated || aggregateChargeBelowFullCycle;
		log.info(
				"[billing-quote/persist] step=aggregate_schedule cycle=1 label={} periodStart={} periodEnd={} billingDate={} baseAmount={} taxAmount={} anyLineProrated={} aggregateChargeBelowFullCycle={}",
				agg.label(), agg.periodStart(), agg.periodEnd(), agg.billingDate(), agg.baseAmount(), agg.taxAmount(),
				anyLineProrated, aggregateChargeBelowFullCycle);

		boolean paidFirstCycle = invoicePaid && invoiceId != null;
		UUID firstCycleScheduleStatusId = paidFirstCycle ? paidScheduleStatusId : plannedScheduleStatusId;
		Timestamp billedOnPaidCycle = paidFirstCycle ? Timestamp.valueOf(LocalDateTime.now(quoteZone)) : null;
		UUID invoiceIdForPaidCycle = paidFirstCycle ? invoiceId : null;

		List<PendingSchedule> pending = buildPendingSchedules(subscriptionLines, recurringRows);
		log.info("[billing-quote/persist] step=billing_schedules_to_insert count={}", pending.size());

		UUID primaryBillingScheduleId = null;
		for (int i = 0; i < pending.size(); i++) {
			PendingSchedule ps = pending.get(i);
			boolean isLast = i == pending.size() - 1;
			UUID schStatus;
			UUID invId;
			Timestamp billedOn;
			UUID billingRunId = null;
			if (ps.fromAgg()) {
				schStatus = firstCycleScheduleStatusId;
				invId = invoiceIdForPaidCycle;
				billedOn = billedOnPaidCycle;
			} else {
				schStatus = plannedScheduleStatusId;
				invId = null;
				billedOn = null;
			}
			ForecastBounds bounds;
			if (ps.fromAgg()) {
				bounds = new ForecastBounds(agg.periodStart(), agg.periodEnd());
			} else {
				bounds = computeForecastPeriodBounds(recurringRows, ps.recurringIndex(), contractStart, contractEnd);
				bounds = resolveBillingPeriodBounds(bounds, ps.recRow().resolvedPeriodLabel(), contractStart, contractEnd);
			}
			LocalDate billDate = ps.fromAgg() ? agg.billingDate()
					: nzDate(ps.recRow().resolvedBillingDate(), bounds.periodStart());
			boolean rowProrated = ps.fromAgg() ? firstCycleProrated : recurringRowIndicatesProration(ps.recRow());
			String prCase = rowProrated ? billing.getProrationCase() : null;
			String prStrat = rowProrated ? billing.getProrationStrategyCode() : null;
			String prSource = rowProrated ? trunc(billing.getProrationSource(), 50) : null;
			UUID sid = insertSubscriptionBillingSchedule(subscriptionInstanceId, subscriptionPlanId,
					ps.cycleNumber(),
					ps.fromAgg() ? agg.label() : trunc(ps.recRow().resolvedScheduleLabel(), 100),
					ps.fromAgg() ? agg.periodLabel()
							: ensurePeriodLabelForBounds(bounds, ps.recRow().resolvedPeriodLabel()),
					bounds.periodStart(), bounds.periodEnd(), billDate,
					ps.fromAgg() ? agg.quantity() : 1,
					ps.fromAgg() ? agg.unitPrice() : nzBd(ps.recRow().resolvedUnitPrice(), BigDecimal.ZERO),
					ps.fromAgg() ? agg.unitPriceBeforeDiscount()
							: nzBd(ps.recRow().resolvedUnitPriceBeforeDiscount(), ps.recRow().resolvedUnitPrice()),
					ps.fromAgg() ? agg.baseAmount() : recurringRowProratedChargeAmount(ps.recRow()),
					BigDecimal.ZERO,
					ps.fromAgg() ? agg.taxAmount() : nzBd(ps.recRow().resolvedTaxAmount(), BigDecimal.ZERO),
					ps.fromAgg() ? agg.taxPct() : nzBd(ps.recRow().resolvedTaxPct(), BigDecimal.ZERO),
					ps.fromAgg() ? agg.subtotalBeforeTax() : recurringRowNetAmount(ps.recRow()),
					rowProrated,
					ps.fromAgg() && isPaidInFull(billing.getFrequencyCode()),
					isLast,
					schStatus,
					prCase,
					prStrat,
					prSource,
					invId,
					billedOn,
					billingRunId);
			if (primaryBillingScheduleId == null) {
				primaryBillingScheduleId = sid;
			}
			if (ps.fromAgg()) {
				log.info("[billing-quote/persist] step=insert_billing_schedule outcome=ok billingScheduleId={} cycle={} fromAgg=true",
						sid, ps.cycleNumber());
				for (QuoteLineItemRow li : subscriptionLines) {
					insertBillingScheduleTaxLinesForQuoteLine(sid, li);
				}
				log.info("[billing-quote/persist] step=insert_schedule_tax_lines outcome=ok lineItemTaxPassCount={}",
						subscriptionLines.size());
				insertSnapshotCyclePricesFromApplied(purchaseSnapshotId, applied);
			} else {
				log.info("[billing-quote/persist] step=insert_billing_schedule outcome=ok billingScheduleId={} cycle={} fromAgg=false",
						sid, ps.cycleNumber());
				insertBillingScheduleTaxLinesForRecurringRow(sid, ps.recRow());
			}
		}

		if (invoiceId != null) {
			InvoiceHeaderAmounts invHdr = loadInvoiceHeaderAmounts(invoiceId);
			log.info(
					"[billing-quote/persist] step=insert_billing_history invoiceId={} invoiceTotalAmount={} invoiceSubTotal={} invoiceTaxAmount={} invoiceDiscountAmount={} billingStatusId={} billingScheduleId={}",
					invoiceId, invHdr.totalAmount(), invHdr.subTotal(), invHdr.taxAmount(), invHdr.discountAmount(),
					billingStatusId, primaryBillingScheduleId);
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
					    billing_schedule_id,
					    invoice_sub_total,
					    invoice_tax_amount,
					    invoice_discount_amount,
					    created_on
					) VALUES (
					    gen_random_uuid(), ?, now(), ?, ?, ?, false, ?, ?, ?, ?, ?, now()
					)
					""",
					subscriptionInstanceId,
					clientPaymentTransactionId,
					billingStatusId,
					invoiceId,
					invHdr.totalAmount(),
					primaryBillingScheduleId,
					invHdr.subTotal(),
					invHdr.taxAmount(),
					invHdr.discountAmount());
			log.info("[billing-quote/persist] step=insert_billing_history outcome=ok");
		} else {
			log.info("[billing-quote/persist] step=insert_billing_history skipped=true reason=null_invoice_id");
		}

		log.info(
				"[billing-quote/persist] step=stack_complete subscriptionPlanId={} subscriptionInstanceId={} primaryBillingScheduleId={} purchaseSnapshotId={} configSnapshotId={}",
				subscriptionPlanId, subscriptionInstanceId, primaryBillingScheduleId, purchaseSnapshotId, configSnapshotId);
	}

	private List<RecurringForecastRow> readRecurringForecastRows(BillingQuoteLineItemsResponse quote) {
		JsonNode recurring = quote.getRecurring();
		if (recurring == null || !recurring.isArray() || recurring.isEmpty()) {
			return List.of();
		}
		List<RecurringForecastRow> out = new ArrayList<>();
		for (JsonNode n : recurring) {
			if (n != null && !n.isNull()) {
				out.add(objectMapper.convertValue(n, RecurringForecastRow.class));
			}
		}
		return out;
	}

	private List<PendingSchedule> buildPendingSchedules(List<QuoteLineItemRow> subscriptionLines,
			List<RecurringForecastRow> recurringRows) {
		List<PendingSchedule> out = new ArrayList<>();
		if (recurringRows == null) {
			recurringRows = List.of();
		}
		if (subscriptionLines == null || subscriptionLines.isEmpty()) {
			int ridx = 0;
			for (RecurringForecastRow r : recurringRows) {
				int cn = resolveForecastCycleNumber(r, ridx);
				out.add(new PendingSchedule(false, r, ridx, cn));
				ridx++;
			}
			return out;
		}
		out.add(new PendingSchedule(true, null, -1, 1));
		int ridx = 0;
		for (RecurringForecastRow r : recurringRows) {
			int cn = resolveForecastCycleNumber(r, ridx);
			/*
			 * Legacy quotes repeated "cycle 1" in recurring[] alongside line-item cycle 1; skip that duplicate. New API
			 * sends billingCycle starting at 2 for the first full billing period, so this rarely triggers.
			 */
			if (cn == 1 && r.getBillingCycle() == null && r.getBillingCycleSnake() == null) {
				log.info(
						"[billing-quote/persist] step=skip_recurring_row reason=duplicate_cycle_1 recurringIndex={}",
						ridx);
				ridx++;
				continue;
			}
			out.add(new PendingSchedule(false, r, ridx, cn));
			ridx++;
		}
		return out;
	}

	private int resolveForecastCycleNumber(RecurringForecastRow r, int index) {
		if (r.getBillingCycle() != null) {
			return r.getBillingCycle();
		}
		if (r.getBillingCycleSnake() != null) {
			return r.getBillingCycleSnake();
		}
		if (r.getCycleNumber() != null) {
			return r.getCycleNumber();
		}
		if (r.getCycleNumberSnake() != null) {
			return r.getCycleNumberSnake();
		}
		String pl = r.resolvedPeriodLabel();
		if (pl != null) {
			Matcher m = CYCLE_LABEL_NUMBER.matcher(pl);
			if (m.find()) {
				return Integer.parseInt(m.group(1));
			}
		}
		return index + 1;
	}

	/**
	 * Derives service-period start/end arrays from recurring quote rows. Per row, {@code nextPeriodStart} is the
	 * anchor for <em>that</em> cycle (same as {@code billingDate} in the API), not the previous row's next anchor.
	 */
	private void fillForecastPeriodArrays(List<RecurringForecastRow> rows, LocalDate contractStart,
			LocalDate contractEnd, LocalDate[] starts, LocalDate[] ends) {
		int n = rows.size();
		for (int i = 0; i < n; i++) {
			RecurringForecastRow r = rows.get(i);
			/* API sends periodStart / period_end explicitly; resolvedPeriodStart falls back to nextPeriodStart. */
			LocalDate exS = r.resolvedPeriodStart();
			if (exS == null) {
				Optional<ForecastBounds> iso = tryParsePeriodBoundsFromLabel(r.resolvedPeriodLabel());
				if (iso.isPresent()) {
					exS = iso.get().periodStart();
				}
			}
			if (exS == null) {
				LocalDate anchor = anchorDateForRecurringRow(r, contractStart);
				Optional<ForecastBounds> mmm = tryParseMonthDayRangeFromPeriodLabel(r.resolvedPeriodLabel(), anchor);
				if (mmm.isPresent()) {
					exS = mmm.get().periodStart();
				}
			}
			if (exS != null) {
				starts[i] = exS;
			} else if (i == 0) {
				starts[i] = contractStart;
			} else {
				RecurringForecastRow prev = rows.get(i - 1);
				LocalDate pEnd = prev.getPeriodEnd() != null ? prev.getPeriodEnd() : prev.getPeriodEndSnake();
				if (pEnd != null) {
					starts[i] = pEnd.plusDays(1);
				} else if (starts[i - 1] != null) {
					starts[i] = starts[i - 1];
				} else {
					starts[i] = contractStart;
				}
			}
		}
		for (int i = 0; i < n; i++) {
			if (starts[i] == null) {
				starts[i] = contractStart;
			}
		}
		for (int i = 0; i < n; i++) {
			RecurringForecastRow r = rows.get(i);
			LocalDate exE = r.resolvedPeriodEnd();
			if (exE != null) {
				ends[i] = exE;
				continue;
			}
			LocalDate anchor = anchorDateForRecurringRow(r, contractStart);
			Optional<ForecastBounds> mmm = tryParseMonthDayRangeFromPeriodLabel(r.resolvedPeriodLabel(), anchor);
			if (mmm.isPresent()) {
				ends[i] = mmm.get().periodEnd();
				continue;
			}
			Optional<ForecastBounds> iso = tryParsePeriodBoundsFromLabel(r.resolvedPeriodLabel());
			if (iso.isPresent()) {
				ends[i] = iso.get().periodEnd();
				continue;
			}
			if (i + 1 < n && starts[i + 1] != null) {
				ends[i] = starts[i + 1].minusDays(1);
			} else {
				ends[i] = contractEnd;
			}
		}
	}

	private static LocalDate anchorDateForRecurringRow(RecurringForecastRow r, LocalDate contractStart) {
		if (r.getNextPeriodStart() != null) {
			return r.getNextPeriodStart();
		}
		if (r.getBillingDate() != null) {
			return r.getBillingDate();
		}
		if (r.getBillingDateSnake() != null) {
			return r.getBillingDateSnake();
		}
		return contractStart;
	}

	private ForecastBounds computeForecastPeriodBounds(List<RecurringForecastRow> rows, int i, LocalDate contractStart,
			LocalDate contractEnd) {
		if (rows == null || rows.isEmpty() || i < 0 || i >= rows.size()) {
			return new ForecastBounds(contractStart, contractEnd);
		}
		LocalDate[] starts = new LocalDate[rows.size()];
		LocalDate[] ends = new LocalDate[rows.size()];
		fillForecastPeriodArrays(rows, contractStart, contractEnd, starts, ends);
		return new ForecastBounds(starts[i], ends[i]);
	}

	/**
	 * Align stored bounds with {@code period_label}: ISO dates, else {@code MMM d-MMM d} inside parentheses (English).
	 */
	private ForecastBounds resolveBillingPeriodBounds(ForecastBounds computed, String periodLabel,
			LocalDate contractStart, LocalDate contractEnd) {
		ForecastBounds computedSafe = orderAndNormalizeBounds(computed, contractStart, contractEnd);
		if (periodLabel == null || periodLabel.isBlank()) {
			return computedSafe;
		}
		String pl = periodLabel.trim();
		Optional<ForecastBounds> parsedIso = tryParsePeriodBoundsFromLabel(pl);
		if (parsedIso.isPresent()) {
			LocalDate ps = clampDateStartToContract(parsedIso.get().periodStart(), contractStart);
			LocalDate pe = parsedIso.get().periodEnd();
			if (ps != null && pe != null && !pe.isBefore(ps)) {
				return new ForecastBounds(ps, pe);
			}
		}
		LocalDate anchor = computedSafe.periodStart() != null ? computedSafe.periodStart() : contractStart;
		Optional<ForecastBounds> parsedMmm = tryParseMonthDayRangeFromPeriodLabel(pl, anchor);
		if (parsedMmm.isPresent()) {
			LocalDate ps = clampDateStartToContract(parsedMmm.get().periodStart(), contractStart);
			LocalDate pe = parsedMmm.get().periodEnd();
			if (ps != null && pe != null && !pe.isBefore(ps)) {
				return new ForecastBounds(ps, pe);
			}
		}
		return computedSafe;
	}

	private static Optional<ForecastBounds> tryParsePeriodBoundsFromLabel(String label) {
		if (label == null || label.isBlank()) {
			return Optional.empty();
		}
		Matcher m = PERIOD_LABEL_ISO_DATES.matcher(label);
		List<LocalDate> found = new ArrayList<>(4);
		while (m.find()) {
			found.add(LocalDate.parse(m.group(1)));
			if (found.size() >= 2) {
				break;
			}
		}
		if (found.size() < 2) {
			return Optional.empty();
		}
		LocalDate a = found.get(0);
		LocalDate b = found.get(1);
		LocalDate start = a.isBefore(b) ? a : b;
		LocalDate end = a.isBefore(b) ? b : a;
		return Optional.of(new ForecastBounds(start, end));
	}

	/**
	 * Parses {@code (May 23-Jun 22)} / {@code (Mar 23-Apr 9)} style ranges; year from {@code anchorHint} (e.g.
	 * {@code nextPeriodStart}).
	 */
	private static Optional<ForecastBounds> tryParseMonthDayRangeFromPeriodLabel(String label, LocalDate anchorHint) {
		if (label == null || label.isBlank()) {
			return Optional.empty();
		}
		Matcher paren = PERIOD_LABEL_PAREN_CONTENT.matcher(label);
		if (!paren.find()) {
			return Optional.empty();
		}
		String inner = paren.group(1).trim();
		inner = inner.replaceFirst("(?i)^cycle\\s*\\d+\\s*", "").trim();
		int dash = inner.indexOf('-');
		if (dash <= 0 || dash >= inner.length() - 1) {
			return Optional.empty();
		}
		String left = inner.substring(0, dash).trim();
		String right = inner.substring(dash + 1).trim();
		int y = anchorHint != null ? anchorHint.getYear() : LocalDate.now().getYear();
		LocalDate s = parseEnglishMonthDayWithYear(left, y);
		LocalDate e = parseEnglishMonthDayWithYear(right, y);
		if (s == null || e == null) {
			return Optional.empty();
		}
		if (e.isBefore(s)) {
			e = e.plusYears(1);
		}
		return Optional.of(new ForecastBounds(s, e));
	}

	private static LocalDate parseEnglishMonthDayWithYear(String token, int year) {
		try {
			return LocalDate.parse(token.trim() + " " + year, MONTH_DAY_EN);
		} catch (DateTimeParseException ex) {
			return null;
		}
	}

	private static ForecastBounds orderAndNormalizeBounds(ForecastBounds b, LocalDate contractStart,
			LocalDate contractEnd) {
		LocalDate s = b.periodStart();
		LocalDate e = b.periodEnd();
		if (s != null && e != null && e.isBefore(s)) {
			LocalDate t = s;
			s = e;
			e = t;
		}
		if (s == null) {
			s = contractStart;
		}
		if (e == null) {
			e = contractEnd;
		}
		if (s != null && e != null && e.isBefore(s)) {
			e = s;
		}
		return new ForecastBounds(s, e);
	}

	private static LocalDate clampDateStartToContract(LocalDate d, LocalDate contractStart) {
		if (d == null) {
			return null;
		}
		if (contractStart != null && d.isBefore(contractStart)) {
			return contractStart;
		}
		return d;
	}

	private static String formatIsoPeriodLabel(LocalDate start, LocalDate end) {
		if (start == null || end == null) {
			return null;
		}
		return start + " – " + end;
	}

	/** Persisted {@code period_label}: quote text, or a compact ISO range aligned with resolved bounds. */
	private static String ensurePeriodLabelForBounds(ForecastBounds bounds, String resolvedPeriodLabel) {
		if (resolvedPeriodLabel != null && !resolvedPeriodLabel.isBlank()) {
			return trunc(resolvedPeriodLabel.trim(), 100);
		}
		return trunc(formatIsoPeriodLabel(bounds.periodStart(), bounds.periodEnd()), 100);
	}

	private UUID insertSubscriptionBillingSchedule(UUID subscriptionInstanceId, UUID subscriptionPlanId,
			int cycleNumber, String label, String periodLabel, LocalDate billingPeriodStart,
			LocalDate billingPeriodEnd, LocalDate billingDate, int quantity, BigDecimal unitPrice,
			BigDecimal unitPriceBeforeDiscount, BigDecimal baseAmount, BigDecimal discountAmount, BigDecimal taxAmount,
			BigDecimal taxPct, BigDecimal subtotalBeforeTax, boolean isProrated, boolean isOneTime, boolean isFinalCycle,
			UUID billingScheduleStatusId, String prorationCaseCode, String prorationStrategyCode, String prorationSource,
			UUID invoiceId, Timestamp billedOn, UUID billingRunId) {
		return jdbc.queryForObject("""
				INSERT INTO client_subscription_billing.subscription_billing_schedule (
				    billing_schedule_id,
				    subscription_instance_id,
				    subscription_plan_id,
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
				    proration_source,
				    invoice_id,
				    billed_on,
				    billing_run_id,
				    created_on
				) VALUES (
				    gen_random_uuid(), ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, now()
				) RETURNING billing_schedule_id
				""", UUID.class,
				subscriptionInstanceId,
				subscriptionPlanId,
				cycleNumber,
				trunc(label, 100),
				trunc(periodLabel, 100),
				billingPeriodStart,
				billingPeriodEnd,
				billingDate,
				quantity,
				unitPrice,
				unitPriceBeforeDiscount,
				baseAmount,
				discountAmount,
				taxAmount,
				taxPct,
				subtotalBeforeTax,
				isProrated,
				isOneTime,
				isFinalCycle,
				billingScheduleStatusId,
				trunc(prorationCaseCode, 50),
				trunc(prorationStrategyCode, 50),
				trunc(prorationSource, 50),
				invoiceId,
				billedOn,
				billingRunId);
	}

	private static BigDecimal recurringRowNetAmount(RecurringForecastRow r) {
		BigDecimal amt = r.getAmount();
		BigDecimal tax = r.resolvedTaxAmount();
		if (amt != null && tax != null) {
			return amt.subtract(tax).max(BigDecimal.ZERO).setScale(2, RoundingMode.HALF_UP);
		}
		if (amt != null) {
			return amt.setScale(2, RoundingMode.HALF_UP);
		}
		return BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
	}

	/** Schedule {@code base_amount}: prorated line charge from quote {@code amount}. */
	private static BigDecimal recurringRowProratedChargeAmount(RecurringForecastRow r) {
		BigDecimal amt = r.getAmount();
		if (amt != null) {
			return amt.setScale(2, RoundingMode.HALF_UP);
		}
		return recurringRowNetAmount(r);
	}

	private static boolean recurringRowIndicatesProration(RecurringForecastRow r) {
		if (r == null) {
			return false;
		}
		BigDecimal amt = r.getAmount();
		BigDecimal up = r.resolvedUnitPrice();
		if (amt == null || up == null) {
			return false;
		}
		return amt.compareTo(up) < 0;
	}

	private static boolean aggregateChargeLessThanFullCycleUnitTotal(List<QuoteLineItemRow> lines) {
		if (lines == null || lines.isEmpty()) {
			return false;
		}
		BigDecimal full = BigDecimal.ZERO;
		BigDecimal charge = BigDecimal.ZERO;
		for (QuoteLineItemRow li : lines) {
			if (li == null) {
				continue;
			}
			int q = nzInt(li.getQuantity(), 1);
			if (li.getUnitPrice() != null) {
				full = full.add(li.getUnitPrice().multiply(BigDecimal.valueOf(q)));
			}
			if (li.getAmount() != null) {
				charge = charge.add(li.getAmount());
			} else if (li.getPrice() != null) {
				charge = charge.add(li.getPrice());
			}
		}
		if (full.compareTo(BigDecimal.ZERO) <= 0) {
			return false;
		}
		return charge.compareTo(full) < 0;
	}

	private InvoiceHeaderAmounts loadInvoiceHeaderAmounts(UUID invoiceId) {
		try {
			return jdbc.queryForObject("""
					SELECT total_amount, sub_total, tax_amount, discount_amount
					FROM transactions.invoice
					WHERE invoice_id = ?
					""",
					(rs, rn) -> new InvoiceHeaderAmounts(rs.getBigDecimal("total_amount"), rs.getBigDecimal("sub_total"),
							rs.getBigDecimal("tax_amount"), rs.getBigDecimal("discount_amount")),
					invoiceId);
		} catch (EmptyResultDataAccessException e) {
			return InvoiceHeaderAmounts.empty();
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
					    created_on
					) VALUES (gen_random_uuid(), ?, ?, ?, ?, ?, now())
					""",
					purchaseSnapshotId,
					r.getPackagePriceId(),
					r.getPackageLocationId(),
					r.getLocationLevelId(),
					r.getPrice());
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
				UUID legalTypeId = purchaseSnapshotLookupDao
						.requirePurchaseSnapshotLegalTypeId(purchaseSnapshotLegalAutoRenewalCode);
				jdbc.update("""
						INSERT INTO client_subscription_billing.subscription_purchase_snapshot_legal (
						    purchase_snapshot_legal_id,
						    subscription_purchase_snapshot_id,
						    purchase_snapshot_line_id,
						    purchase_snapshot_legal_type_id,
						    legal_text,
						    language_code,
						    created_on
						) VALUES (gen_random_uuid(), ?, NULL, ?, ?, ?, now())
						""",
						purchaseSnapshotId,
						legalTypeId,
						li.getDisclosureAutoRenewal().trim(),
						purchaseSnapshotLegalDefaultLanguageCode);
				legalRows++;
			}
			if (li.getDisclosureMinTerm() != null && !li.getDisclosureMinTerm().isBlank()) {
				UUID legalTypeId = purchaseSnapshotLookupDao
						.requirePurchaseSnapshotLegalTypeId(purchaseSnapshotLegalMinTermCode);
				jdbc.update("""
						INSERT INTO client_subscription_billing.subscription_purchase_snapshot_legal (
						    purchase_snapshot_legal_id,
						    subscription_purchase_snapshot_id,
						    purchase_snapshot_line_id,
						    purchase_snapshot_legal_type_id,
						    legal_text,
						    language_code,
						    created_on
						) VALUES (gen_random_uuid(), ?, NULL, ?, ?, ?, now())
						""",
						purchaseSnapshotId,
						legalTypeId,
						li.getDisclosureMinTerm().trim(),
						purchaseSnapshotLegalDefaultLanguageCode);
				legalRows++;
			}
		}
		log.info("[billing-quote/persist] step=insert_purchase_snapshot_legal outcome=ok legalRowsInserted={}",
				legalRows);
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
			int cycleStart = ap.getStartCycle() != null ? ap.getStartCycle()
					: (ap.getCycleNumber() != null ? ap.getCycleNumber() : 1);
			Integer cycleEnd = ap.getEndCycle();
			jdbc.update("""
					INSERT INTO client_subscription_billing.subscription_purchase_snapshot_cycle_price (
					    purchase_snapshot_cycle_price_id,
					    subscription_purchase_snapshot_id,
					    cycle_start,
					    cycle_end,
					    unit_price,
					    price_cycle_band_id,
					    created_on
					) VALUES (gen_random_uuid(), ?, ?, ?, ?, ?, now())
					""",
					purchaseSnapshotId,
					cycleStart,
					cycleEnd,
					ap.getUnitPrice(),
					ap.getPriceCycleBandId());
			c++;
		}
		log.info("[billing-quote/persist] step=insert_snapshot_cycle_prices outcome=ok rowsInserted={}", c);
	}

	private List<QuoteLineItemRow> nonFeeLineItems(List<QuoteLineItemRow> lines) {
		if (lines == null || lines.isEmpty()) {
			return List.of();
		}
		return lines.stream().filter(li -> li != null && !isFeeItemGroupLine(li)).toList();
	}

	/**
	 * Fee lines are excluded from subscription line-item inserts. Treat {@code isFeeItem == true} like FEE when
	 * {@code itemGroupCode} is absent.
	 */
	private boolean isFeeItemGroupLine(QuoteLineItemRow li) {
		if (li == null) {
			return false;
		}
		if (Boolean.TRUE.equals(li.getIsFeeItem())) {
			return true;
		}
		String code = li.getItemGroupCode();
		if (code == null || code.isBlank()) {
			return false;
		}
		String trimmed = code.trim();
		for (String part : feeItemGroupCodes.split(",")) {
			if (part != null && !part.isBlank() && part.trim().equalsIgnoreCase(trimmed)) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Resolves {@code tax_name} for snapshot/tax-line inserts (NOT NULL in DB). Prefers tax authority name when
	 * allocation is present, else tax group name from {@code finance.tax_rate}.
	 */
	private String resolveTaxNameForPersist(UUID taxRateId, UUID taxRateAllocationId) {
		if (taxRateAllocationId != null) {
			try {
				String n = jdbc.queryForObject("""
						SELECT ta.name
						FROM finance.tax_rate_allocation tra
						JOIN finance.tax_authority ta ON ta.tax_authority_id = tra.tax_authority_id
						WHERE tra.tax_rate_allocation_id = ?
						LIMIT 1
						""", String.class, taxRateAllocationId);
				if (n != null && !n.isBlank()) {
					return trunc(n, 100);
				}
			} catch (EmptyResultDataAccessException ignored) {
				// fall through to rate-level name
			}
		}
		if (taxRateId != null) {
			try {
				String n = jdbc.queryForObject("""
						SELECT tg.name
						FROM finance.tax_rate tr
						JOIN finance.tax_group tg ON tg.tax_group_id = tr.tax_group_id
						WHERE tr.tax_rate_id = ?
						LIMIT 1
						""", String.class, taxRateId);
				if (n != null && !n.isBlank()) {
					return trunc(n, 100);
				}
			} catch (EmptyResultDataAccessException ignored) {
				// fall through
			}
		}
		log.warn(
				"[billing-quote/persist] step=resolve_tax_name outcome=fallback_default taxRateId={} taxRateAllocationId={}",
				taxRateId, taxRateAllocationId);
		return purchaseSnapshotTaxFallbackName;
	}

	/**
	 * Resolves {@code tax_percentage} (NOT NULL in DB): allocation row when id present, else sum of active
	 * allocations for the rate, else {@code fallbackFromQuote} (e.g. line item or recurring {@code taxPct}).
	 */
	private BigDecimal resolveTaxPercentageForPersist(UUID taxRateId, UUID taxRateAllocationId,
			BigDecimal fallbackFromQuote) {
		try {
			if (taxRateAllocationId != null) {
				BigDecimal p = jdbc.queryForObject("""
						SELECT tra.tax_rate_percentage
						FROM finance.tax_rate_allocation tra
						WHERE tra.tax_rate_allocation_id = ?
						LIMIT 1
						""", BigDecimal.class, taxRateAllocationId);
				if (p != null) {
					return p.setScale(2, RoundingMode.HALF_UP);
				}
			}
			if (taxRateId != null) {
				BigDecimal sum = jdbc.queryForObject("""
						SELECT COALESCE(SUM(tra.tax_rate_percentage), 0)
						FROM finance.tax_rate_allocation tra
						WHERE tra.tax_rate_id = ?
						  AND COALESCE(tra.is_active, TRUE) = TRUE
						""", BigDecimal.class, taxRateId);
				if (sum != null && sum.compareTo(BigDecimal.ZERO) > 0) {
					return sum.setScale(2, RoundingMode.HALF_UP);
				}
			}
		} catch (EmptyResultDataAccessException ignored) {
			// fall through
		}
		if (fallbackFromQuote != null) {
			return fallbackFromQuote.setScale(2, RoundingMode.HALF_UP);
		}
		log.warn(
				"[billing-quote/persist] step=resolve_tax_percentage outcome=fallback_zero taxRateId={} taxRateAllocationId={}",
				taxRateId, taxRateAllocationId);
		return BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
	}

	/**
	 * Deduped snapshot-level tax rows from non-FEE line items (expects
	 * {@code subscription_purchase_snapshot_tax} in DB, including NOT NULL {@code tax_name}).
	 */
	private void insertPurchaseSnapshotTaxRows(UUID purchaseSnapshotId, List<QuoteLineItemRow> subscriptionLines) {
		if (purchaseSnapshotId == null || subscriptionLines == null || subscriptionLines.isEmpty()) {
			log.info(
					"[billing-quote/persist] step=insert_purchase_snapshot_tax skipped=true reason=no_rows purchaseSnapshotId={}",
					purchaseSnapshotId);
			return;
		}
		Set<String> seen = new HashSet<>();
		int n = 0;
		for (QuoteLineItemRow li : subscriptionLines) {
			UUID rateId = li.resolvedTaxRateId();
			if (rateId == null) {
				continue;
			}
			List<UUID> allocs = li.resolvedTaxRateAllocationIds();
			if (allocs.isEmpty()) {
				String k = rateId + ":null";
				if (seen.add(k)) {
					String taxName = resolveTaxNameForPersist(rateId, null);
					BigDecimal taxPct = resolveTaxPercentageForPersist(rateId, null, li.getTaxPct());
					jdbc.update("""
							INSERT INTO client_subscription_billing.subscription_purchase_snapshot_tax (
							    purchase_snapshot_tax_id,
							    subscription_purchase_snapshot_id,
							    tax_rate_id,
							    tax_rate_allocation_id,
							    tax_name,
							    tax_code,
							    tax_percentage,
							    created_on
							) VALUES (gen_random_uuid(), ?, ?, ?, ?, NULL, ?, now())
							""",
							purchaseSnapshotId, rateId, null, taxName, taxPct);
					n++;
				}
				continue;
			}
			for (UUID allocId : allocs) {
				if (allocId == null) {
					continue;
				}
				String k = rateId + ":" + allocId;
				if (seen.add(k)) {
					String taxName = resolveTaxNameForPersist(rateId, allocId);
					BigDecimal taxPct = resolveTaxPercentageForPersist(rateId, allocId, li.getTaxPct());
					jdbc.update("""
							INSERT INTO client_subscription_billing.subscription_purchase_snapshot_tax (
							    purchase_snapshot_tax_id,
							    subscription_purchase_snapshot_id,
							    tax_rate_id,
							    tax_rate_allocation_id,
							    tax_name,
							    tax_code,
							    tax_percentage,
							    created_on
							) VALUES (gen_random_uuid(), ?, ?, ?, ?, NULL, ?, now())
							""",
							purchaseSnapshotId, rateId, allocId, taxName, taxPct);
					n++;
				}
			}
		}
		log.info("[billing-quote/persist] step=insert_purchase_snapshot_tax outcome=ok rowsInserted={}", n);
	}

	/** One row per allocation (or one row with null allocation when none listed). No schedule line-item row. */
	private void insertBillingScheduleTaxLinesForQuoteLine(UUID billingScheduleId, QuoteLineItemRow li) {
		if (billingScheduleId == null || li == null) {
			return;
		}
		UUID rateId = li.resolvedTaxRateId();
		if (rateId == null) {
			return;
		}
		BigDecimal taxableBase = lineItemTaxableBase(li);
		BigDecimal totalLineTax = nzBd(li.getTax(), BigDecimal.ZERO);
		List<UUID> allocs = li.resolvedTaxRateAllocationIds();
		if (allocs.isEmpty()) {
			String taxName = resolveTaxNameForPersist(rateId, null);
			BigDecimal taxPct = resolveTaxPercentageForPersist(rateId, null, li.getTaxPct());
			jdbc.update("""
					INSERT INTO client_subscription_billing.subscription_billing_schedule_tax_line (
					    billing_schedule_id,
					    tax_rate_id,
					    tax_rate_allocation_id,
					    tax_name,
					    tax_percentage,
					    taxable_amount,
					    tax_amount,
					    created_on
					) VALUES (?, ?, NULL, ?, ?, ?, ?, now())
					""",
					billingScheduleId, rateId, taxName, taxPct, taxableBase, totalLineTax);
			return;
		}
		Map<UUID, BigDecimal> pctByAlloc = new HashMap<>();
		BigDecimal sumPct = BigDecimal.ZERO;
		int validAllocCount = 0;
		for (UUID aid : allocs) {
			if (aid == null) {
				continue;
			}
			validAllocCount++;
			BigDecimal p = resolveTaxPercentageForPersist(rateId, aid, li.getTaxPct());
			pctByAlloc.put(aid, p);
			sumPct = sumPct.add(p != null ? p : BigDecimal.ZERO);
		}
		for (UUID allocId : allocs) {
			if (allocId == null) {
				continue;
			}
			String taxName = resolveTaxNameForPersist(rateId, allocId);
			BigDecimal taxPct = resolveTaxPercentageForPersist(rateId, allocId, li.getTaxPct());
			BigDecimal taxAmtPart = splitLineTaxForAllocation(totalLineTax, pctByAlloc.get(allocId), sumPct,
					validAllocCount);
			jdbc.update("""
					INSERT INTO client_subscription_billing.subscription_billing_schedule_tax_line (
					    billing_schedule_id,
					    tax_rate_id,
					    tax_rate_allocation_id,
					    tax_name,
					    tax_percentage,
					    taxable_amount,
					    tax_amount,
					    created_on
					) VALUES (?, ?, ?, ?, ?, ?, ?, now())
					""",
					billingScheduleId, rateId, allocId, taxName, taxPct, taxableBase, taxAmtPart);
		}
	}

	/**
	 * Per-allocation (or single null-allocation) tax breakdown for one purchase snapshot line.
	 */
	private void insertPurchaseSnapshotLineTaxRows(UUID purchaseSnapshotLineId, QuoteLineItemRow li) {
		if (purchaseSnapshotLineId == null || li == null) {
			return;
		}
		UUID rateId = li.resolvedTaxRateId();
		if (rateId == null) {
			return;
		}
		BigDecimal taxableBase = lineItemTaxableBase(li);
		BigDecimal taxableScaled = taxableBase != null ? taxableBase.setScale(4, RoundingMode.HALF_UP) : null;
		BigDecimal totalLineTax = nzBd(li.getTax(), BigDecimal.ZERO);
		List<UUID> allocs = li.resolvedTaxRateAllocationIds();
		if (allocs.isEmpty()) {
			String taxName = resolveTaxNameForPersist(rateId, null);
			BigDecimal taxPct = resolveTaxPercentageForPersist(rateId, null, li.getTaxPct());
			jdbc.update("""
					INSERT INTO client_subscription_billing.subscription_purchase_snapshot_line_tax (
					    purchase_snapshot_line_tax_id,
					    purchase_snapshot_line_id,
					    tax_rate_id,
					    tax_rate_allocation_id,
					    tax_name,
					    tax_code,
					    tax_percentage,
					    taxable_amount,
					    tax_amount,
					    created_on
					) VALUES (gen_random_uuid(), ?, ?, ?, ?, NULL, ?, ?, ?, now())
					""",
					purchaseSnapshotLineId,
					rateId,
					null,
					taxName,
					taxPct,
					taxableScaled,
					totalLineTax.setScale(4, RoundingMode.HALF_UP));
			return;
		}
		Map<UUID, BigDecimal> pctByAlloc = new HashMap<>();
		BigDecimal sumPct = BigDecimal.ZERO;
		int validAllocCount = 0;
		for (UUID aid : allocs) {
			if (aid == null) {
				continue;
			}
			validAllocCount++;
			BigDecimal p = resolveTaxPercentageForPersist(rateId, aid, li.getTaxPct());
			pctByAlloc.put(aid, p);
			sumPct = sumPct.add(p != null ? p : BigDecimal.ZERO);
		}
		for (UUID allocId : allocs) {
			if (allocId == null) {
				continue;
			}
			String taxName = resolveTaxNameForPersist(rateId, allocId);
			BigDecimal taxPct = resolveTaxPercentageForPersist(rateId, allocId, li.getTaxPct());
			BigDecimal taxAmtPart = splitLineTaxForAllocation(totalLineTax, pctByAlloc.get(allocId), sumPct,
					validAllocCount);
			jdbc.update("""
					INSERT INTO client_subscription_billing.subscription_purchase_snapshot_line_tax (
					    purchase_snapshot_line_tax_id,
					    purchase_snapshot_line_id,
					    tax_rate_id,
					    tax_rate_allocation_id,
					    tax_name,
					    tax_code,
					    tax_percentage,
					    taxable_amount,
					    tax_amount,
					    created_on
					) VALUES (gen_random_uuid(), ?, ?, ?, ?, NULL, ?, ?, ?, now())
					""",
					purchaseSnapshotLineId,
					rateId,
					allocId,
					taxName,
					taxPct,
					taxableScaled,
					taxAmtPart.setScale(4, RoundingMode.HALF_UP));
		}
	}

	private void insertBillingScheduleTaxLinesForRecurringRow(UUID billingScheduleId, RecurringForecastRow r) {
		if (billingScheduleId == null || r == null) {
			return;
		}
		UUID rateId = r.resolvedTaxRateId();
		if (rateId == null) {
			return;
		}
		BigDecimal taxableBase = recurringRowNetAmount(r);
		BigDecimal totalLineTax = nzBd(r.resolvedTaxAmount(), BigDecimal.ZERO);
		List<UUID> allocs = r.resolvedTaxRateAllocationIds();
		if (allocs.isEmpty()) {
			String taxName = resolveTaxNameForPersist(rateId, null);
			BigDecimal taxPct = resolveTaxPercentageForPersist(rateId, null, r.resolvedTaxPct());
			jdbc.update("""
					INSERT INTO client_subscription_billing.subscription_billing_schedule_tax_line (
					    billing_schedule_id,
					    tax_rate_id,
					    tax_rate_allocation_id,
					    tax_name,
					    tax_percentage,
					    taxable_amount,
					    tax_amount,
					    created_on
					) VALUES (?, ?, NULL, ?, ?, ?, ?, now())
					""",
					billingScheduleId, rateId, taxName, taxPct, taxableBase, totalLineTax);
			return;
		}
		Map<UUID, BigDecimal> pctByAlloc = new HashMap<>();
		BigDecimal sumPct = BigDecimal.ZERO;
		int validAllocCount = 0;
		for (UUID aid : allocs) {
			if (aid == null) {
				continue;
			}
			validAllocCount++;
			BigDecimal p = resolveTaxPercentageForPersist(rateId, aid, r.resolvedTaxPct());
			pctByAlloc.put(aid, p);
			sumPct = sumPct.add(p != null ? p : BigDecimal.ZERO);
		}
		for (UUID allocId : allocs) {
			if (allocId == null) {
				continue;
			}
			String taxName = resolveTaxNameForPersist(rateId, allocId);
			BigDecimal taxPct = resolveTaxPercentageForPersist(rateId, allocId, r.resolvedTaxPct());
			BigDecimal taxAmtPart = splitLineTaxForAllocation(totalLineTax, pctByAlloc.get(allocId), sumPct,
					validAllocCount);
			jdbc.update("""
					INSERT INTO client_subscription_billing.subscription_billing_schedule_tax_line (
					    billing_schedule_id,
					    tax_rate_id,
					    tax_rate_allocation_id,
					    tax_name,
					    tax_percentage,
					    taxable_amount,
					    tax_amount,
					    created_on
					) VALUES (?, ?, ?, ?, ?, ?, ?, now())
					""",
					billingScheduleId, rateId, allocId, taxName, taxPct, taxableBase, taxAmtPart);
		}
	}

	/** Subtotal / amount the tax applies to (quote line {@code price}, then {@code amount}). */
	private static BigDecimal lineItemTaxableBase(QuoteLineItemRow li) {
		if (li.getPrice() != null) {
			return li.getPrice().setScale(2, RoundingMode.HALF_UP);
		}
		if (li.getAmount() != null) {
			return li.getAmount().setScale(2, RoundingMode.HALF_UP);
		}
		return BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
	}

	private static BigDecimal nzBd(BigDecimal v, BigDecimal d) {
		return v != null ? v : d;
	}

	/**
	 * First cycle billing date: {@code recurring[0].billingDate} when present, else earliest
	 * {@code lineItems[].billingDate} (by {@code sequence}), else contract start.
	 */
	private LocalDate resolveFirstCycleBillingDate(BillingQuoteLineItemsResponse quote,
			List<QuoteLineItemRow> subscriptionLines, LocalDate contractStart) {
		JsonNode recurring = quote.getRecurring();
		if (recurring != null && recurring.isArray() && !recurring.isEmpty()) {
			JsonNode n = recurring.get(0);
			if (n != null && !n.isNull()) {
				try {
					RecurringForecastRow r = objectMapper.convertValue(n, RecurringForecastRow.class);
					LocalDate bd = r.resolvedBillingDate();
					if (bd != null) {
						return bd;
					}
				} catch (IllegalArgumentException ignored) {
					// fall through
				}
			}
		}
		LocalDate lineBd = firstLineItemBillingDateOrdered(subscriptionLines);
		if (lineBd != null) {
			return lineBd;
		}
		return contractStart;
	}

	/**
	 * When invoice is paid: first line item's {@code billingDate} (by {@code sequence}), else "today" in quote
	 * timezone. When not paid: {@code null}.
	 */
	private static LocalDate resolveLastBilledOn(List<QuoteLineItemRow> subscriptionLines, boolean invoicePaid,
			LocalDate todayInQuoteTimezone) {
		if (!invoicePaid) {
			return null;
		}
		LocalDate first = firstLineItemBillingDateOrdered(subscriptionLines);
		return first != null ? first : todayInQuoteTimezone;
	}

	private static LocalDate firstLineItemBillingDateOrdered(List<QuoteLineItemRow> lines) {
		if (lines == null || lines.isEmpty()) {
			return null;
		}
		return lines.stream()
				.filter(Objects::nonNull)
				.filter(li -> li.getBillingDate() != null)
				.min(Comparator.comparing((QuoteLineItemRow li) -> nzInt(li.getSequence(), Integer.MAX_VALUE))
						.thenComparing(QuoteLineItemRow::getBillingDate))
				.map(QuoteLineItemRow::getBillingDate)
				.orElse(null);
	}

	private static int nzInt(Integer v, int defaultVal) {
		return v != null ? v : defaultVal;
	}

	/**
	 * When multiple {@code tax_rate_allocation} rows exist for one line, split {@code totalLineTax} by each
	 * allocation's rate share; otherwise equal split; single row gets full tax.
	 */
	private static BigDecimal splitLineTaxForAllocation(BigDecimal totalLineTax, BigDecimal thisAllocPct,
			BigDecimal sumAllocPct, int validAllocCount) {
		if (totalLineTax == null || totalLineTax.compareTo(BigDecimal.ZERO) == 0) {
			return BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
		}
		if (sumAllocPct != null && sumAllocPct.compareTo(BigDecimal.ZERO) > 0 && thisAllocPct != null) {
			return totalLineTax.multiply(thisAllocPct).divide(sumAllocPct, 2, RoundingMode.HALF_UP);
		}
		if (validAllocCount > 0) {
			return totalLineTax.divide(BigDecimal.valueOf(validAllocCount), 2, RoundingMode.HALF_UP);
		}
		return totalLineTax.setScale(2, RoundingMode.HALF_UP);
	}

	/**
	 * Fee-only cart: no {@code subscription_plan} / instance / schedules; persists config + purchase snapshot,
	 * receipt lines, legal/tax, and package snapshot rows for audit.
	 */
	private void persistFeeOnlyQuoteSnapshot(BillingQuoteLineItemsResponse quote, BillingSection billing,
			PlanPosDetailSection pos, List<QuoteLineItemRow> lines, UUID clientPaymentMethodId, UUID clientAgreementId,
			UUID invoiceId, UUID clientPaymentTransactionId, UUID transactionId, UUID createdBy) {
		UUID billingPeriodUnitId = billingLookup.requireBillingPeriodUnitIdByCode(billing.getFrequencyCode(),
				pifBillingPeriodFallbackCode);
		UUID chargeTriggerId = billingLookup.requireChargeTriggerTypeId(billing.getChargeTriggerTypeCode());
		UUID chargeEndId = billingLookup.requireChargeEndConditionId(billing.getChargeEndConditionCode());
		UUID billingTimingId = billingLookup.requireBillingTimingId(billing.getBillingTimingCode());
		UUID billingAlignmentId = billingLookup.requireBillingAlignmentId(billing.getBillingAlignmentCode());
		UUID prorationStrategyId = billingLookup.requireProrationStrategyId(billing.getProrationStrategyCode());
		UUID dayRuleId = billingLookup.findSubscriptionBillingDayRuleIdByTermConfig(pos.getPackagePlanTemplateTermConfigId());
		ZoneId zone = parseZone(quote.getTimezone());
		LocalDate contractStart = nzDate(pos.getBillingStartDate(), quote.getStartDate());
		LocalDate contractEnd = nzDate(pos.getBillingEndDate(), contractStart);
		Instant triggerAt = contractStart != null ? contractStart.atStartOfDay(zone).toInstant() : Instant.now();
		UUID configSnapshotId = jdbc.queryForObject("""
				INSERT INTO client_subscription_billing.subscription_billing_config_snapshot (
				    subscription_billing_config_snapshot_id,
				    subscription_plan_id,
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
				    gen_random_uuid(), NULL, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?
				) RETURNING subscription_billing_config_snapshot_id
				""", UUID.class,
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
		UUID purchaseSnapshotKindId = purchaseSnapshotLookupDao
				.requirePurchaseSnapshotKindId(purchaseSnapshotKindFinalizeCode);
		String presentationJson = quotePresentationJson(quote);
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
				    invoice_id,
				    client_payment_transaction_id,
				    transaction_id,
				    purchase_snapshot_kind_id,
				    presentation_json,
				    captured_by
				) VALUES (
				    gen_random_uuid(), NULL, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CAST(? AS jsonb), ?
				) RETURNING subscription_purchase_snapshot_id
				""", UUID.class,
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
				invoiceId,
				clientPaymentTransactionId,
				transactionId,
				purchaseSnapshotKindId,
				presentationJson,
				createdBy);
		insertPurchaseSnapshotPrices(purchaseSnapshotId, pos.getPackagePrices());
		insertPurchaseSnapshotEntitlements(purchaseSnapshotId, pos.getEntitlements());
		insertPurchaseSnapshotLegalRows(purchaseSnapshotId, lines);
		insertPurchaseSnapshotTaxRows(purchaseSnapshotId, lines);
		Map<Integer, UUID> snapshotLineIdsBySequence = insertPurchaseSnapshotLines(purchaseSnapshotId, lines, null, null,
				pos);
		insertPurchaseSnapshotPromosFromQuoteRoot(purchaseSnapshotId, quote, snapshotLineIdsBySequence);
		log.info(
				"[billing-quote/persist] step=fee_only_snapshot_complete purchaseSnapshotId={} configSnapshotId={}",
				purchaseSnapshotId, configSnapshotId);
	}

	private String quotePresentationJson(BillingQuoteLineItemsResponse quote) {
		if (quote == null) {
			return null;
		}
		try {
			return objectMapper.writeValueAsString(quote);
		} catch (JsonProcessingException e) {
			log.warn("[billing-quote/persist] step=presentation_json outcome=skip reason=serialize_failed", e);
			return null;
		}
	}

	/**
	 * One row per quote line (including fees): denormalized receipt for the purchase snapshot.
	 *
	 * @return map of persisted {@code line_sequence} (1-based order after sort) → {@code purchase_snapshot_line_id}
	 */
	private Map<Integer, UUID> insertPurchaseSnapshotLines(UUID purchaseSnapshotId, List<QuoteLineItemRow> allLines,
			UUID subscriptionPlanId, UUID subscriptionInstanceId, PlanPosDetailSection pos) {
		Map<Integer, UUID> lineSequenceToId = new HashMap<>();
		if (purchaseSnapshotId == null || allLines == null || allLines.isEmpty()) {
			return lineSequenceToId;
		}
		List<QuoteLineItemRow> ordered = new ArrayList<>(allLines);
		ordered.sort(Comparator.comparing(li -> nzInt(li != null ? li.getSequence() : null, Integer.MAX_VALUE)));
		int seq = 0;
		for (QuoteLineItemRow li : ordered) {
			if (li == null) {
				continue;
			}
			seq++;
			boolean fee = isFeeItemGroupLine(li);
			String lineTypeCode = fee ? purchaseSnapshotLineTypeFeeCode : purchaseSnapshotLineTypeSubscriptionCode;
			UUID lineTypeId = purchaseSnapshotLookupDao.requirePurchaseSnapshotLineTypeId(lineTypeCode);
			String title = li.getLabel() != null && !li.getLabel().isBlank() ? li.getLabel().trim() : ("Line " + seq);
			BigDecimal qtyBd = BigDecimal.valueOf(nzInt(li.getQuantity(), 1));
			BigDecimal sub = lineItemSubtotalForSnapshot(li);
			BigDecimal unit = unitPriceForSnapshotLine(li, qtyBd, sub);
			BigDecimal tax = li.getTax() != null ? li.getTax().setScale(4, RoundingMode.HALF_UP) : null;
			BigDecimal disc = BigDecimal.ZERO.setScale(4, RoundingMode.HALF_UP);
			BigDecimal total = li.getAmount() != null ? li.getAmount().setScale(4, RoundingMode.HALF_UP) : sub;
			UUID lineId = jdbc.queryForObject("""
					INSERT INTO client_subscription_billing.subscription_purchase_snapshot_line (
					    purchase_snapshot_line_id,
					    subscription_purchase_snapshot_id,
					    parent_purchase_snapshot_line_id,
					    line_sequence,
					    purchase_snapshot_line_type_id,
					    display_title,
					    display_subtitle,
					    item_group_code,
					    sku_or_item_code,
					    quantity,
					    unit_price,
					    line_subtotal,
					    tax_amount,
					    discount_amount,
					    line_total,
					    billing_date,
					    period_label,
					    is_recurring,
					    is_prorated,
					    is_fee,
					    subscription_plan_id,
					    subscription_instance_id,
					    invoice_entity_id,
					    package_item_id,
					    package_plan_template_id,
					    attributes_json,
					    created_on
					) VALUES (
					    gen_random_uuid(), ?, NULL, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, NULL, ?, ?, NULL, now()
					)
					RETURNING purchase_snapshot_line_id
					""",
					UUID.class,
					purchaseSnapshotId,
					seq,
					lineTypeId,
					trunc(title, 500),
					null,
					trunc(li.getItemGroupCode(), 64),
					null,
					qtyBd,
					unit,
					sub,
					tax,
					disc,
					total,
					li.getBillingDate(),
					trunc(periodLabelForSnapshotLine(li), 200),
					!fee,
					Boolean.TRUE.equals(li.getIsProrated()),
					fee,
					fee ? null : subscriptionPlanId,
					fee ? null : subscriptionInstanceId,
					fee ? null : pos.getPackageItemId(),
					fee ? null : pos.getPackagePlanTemplateId());
			lineSequenceToId.put(seq, lineId);
			insertPurchaseSnapshotLineTaxRows(lineId, li);
			insertPurchaseSnapshotPromoRowsForLine(purchaseSnapshotId, lineId, li.getPromotions());
		}
		log.info("[billing-quote/persist] step=insert_purchase_snapshot_lines outcome=ok rowsInserted={}", seq);
		return lineSequenceToId;
	}

	private void insertPurchaseSnapshotPromoRowsForLine(UUID purchaseSnapshotId, UUID purchaseSnapshotLineId,
			List<QuotePromotionRow> promos) {
		if (purchaseSnapshotId == null || purchaseSnapshotLineId == null || CollectionUtils.isEmpty(promos)) {
			return;
		}
		for (QuotePromotionRow p : promos) {
			insertOnePurchaseSnapshotPromo(purchaseSnapshotId, purchaseSnapshotLineId, p);
		}
	}

	private void insertPurchaseSnapshotPromosFromQuoteRoot(UUID purchaseSnapshotId, BillingQuoteLineItemsResponse quote,
			Map<Integer, UUID> lineSequenceToLineId) {
		if (purchaseSnapshotId == null || quote == null) {
			return;
		}
		List<QuotePromotionRow> root = readQuotePromotions(quote);
		if (CollectionUtils.isEmpty(root)) {
			return;
		}
		for (QuotePromotionRow p : root) {
			if (p == null) {
				continue;
			}
			UUID lineId = null;
			if (p.getLineSequence() != null) {
				lineId = lineSequenceToLineId.get(p.getLineSequence());
				if (lineId == null) {
					log.warn(
							"[billing-quote/persist] step=purchase_snapshot_promo outcome=skip reason=no_line_for_sequence lineSequence={}",
							p.getLineSequence());
					continue;
				}
			}
			insertOnePurchaseSnapshotPromo(purchaseSnapshotId, lineId, p);
		}
	}

	private void insertOnePurchaseSnapshotPromo(UUID purchaseSnapshotId, UUID purchaseSnapshotLineId,
			QuotePromotionRow row) {
		if (purchaseSnapshotId == null || row == null) {
			return;
		}
		UUID promotionVersionId = row.resolvedPromotionVersionId();
		if (promotionVersionId == null) {
			return;
		}
		int cycleStartRaw = row.getCycleStart() != null ? row.getCycleStart() : purchaseSnapshotPromoDefaultCycleStart;
		int cycleStart = Math.max(1, cycleStartRaw);
		Integer cycleEnd = row.getCycleEnd();
		if (cycleEnd != null && cycleEnd < cycleStart) {
			log.warn(
					"[billing-quote/persist] step=purchase_snapshot_promo outcome=cycle_end_clamped promotionVersionId={} cycleStart={} cycleEnd={}",
					promotionVersionId, cycleStart, cycleEnd);
			cycleEnd = null;
		}
		Optional<UUID> discountTypeId = Optional.empty();
		if (row.getDiscountTypeCode() != null && !row.getDiscountTypeCode().isBlank()) {
			discountTypeId = purchaseSnapshotLookupDao
					.findPurchaseSnapshotPromoDiscountTypeId(row.getDiscountTypeCode().trim());
			if (discountTypeId.isEmpty()) {
				log.warn(
						"[billing-quote/persist] step=purchase_snapshot_promo outcome=discount_type_unresolved code={}",
						row.getDiscountTypeCode().trim());
			}
		}
		BigDecimal discountVal = row.getDiscountValue() != null
				? row.getDiscountValue().setScale(3, RoundingMode.HALF_UP)
				: null;
		jdbc.update("""
				INSERT INTO client_subscription_billing.subscription_purchase_snapshot_promo (
				    purchase_snapshot_promo_id,
				    subscription_purchase_snapshot_id,
				    purchase_snapshot_line_id,
				    promotion_version_id,
				    promotion_effect_id,
				    cycle_start,
				    cycle_end,
				    price_cycle_band_id,
				    purchase_snapshot_promo_discount_type_id,
				    discount_value,
				    created_on
				) VALUES (gen_random_uuid(), ?, ?, ?, ?, ?, ?, ?, ?, ?, now())
				""",
				purchaseSnapshotId,
				purchaseSnapshotLineId,
				promotionVersionId,
				row.getPromotionEffectId(),
				cycleStart,
				cycleEnd,
				row.getPriceCycleBandId(),
				discountTypeId.orElse(null),
				discountVal);
	}

	private static BigDecimal lineItemSubtotalForSnapshot(QuoteLineItemRow li) {
		if (li.getPrice() != null) {
			return li.getPrice().setScale(4, RoundingMode.HALF_UP);
		}
		if (li.getAmount() != null) {
			return li.getAmount().setScale(4, RoundingMode.HALF_UP);
		}
		return BigDecimal.ZERO.setScale(4, RoundingMode.HALF_UP);
	}

	/**
	 * Receipt-style unit: when quote {@code price} (subtotal) does not match {@code unitPrice × quantity} (e.g.
	 * proration), persist subtotal/qty so {@code line_subtotal} aligns with {@code unit_price × quantity}.
	 */
	private static BigDecimal unitPriceForSnapshotLine(QuoteLineItemRow li, BigDecimal qtyBd, BigDecimal sub) {
		if (li == null || qtyBd == null || qtyBd.compareTo(BigDecimal.ZERO) <= 0) {
			return li != null && li.getUnitPrice() != null ? li.getUnitPrice().setScale(4, RoundingMode.HALF_UP) : null;
		}
		if (sub == null) {
			return li.getUnitPrice() != null ? li.getUnitPrice().setScale(4, RoundingMode.HALF_UP) : null;
		}
		BigDecimal listUnit = li.getUnitPrice() != null ? li.getUnitPrice().setScale(4, RoundingMode.HALF_UP) : null;
		if (listUnit != null) {
			BigDecimal listExtended = listUnit.multiply(qtyBd).setScale(4, RoundingMode.HALF_UP);
			if (listExtended.compareTo(sub) != 0) {
				return sub.divide(qtyBd, 4, RoundingMode.HALF_UP);
			}
			return listUnit;
		}
		return sub.divide(qtyBd, 4, RoundingMode.HALF_UP);
	}

	/** Prefer quote {@code period_label}; else a compact range from line {@code startDate}/{@code endDate} when both set. */
	private static String periodLabelForSnapshotLine(QuoteLineItemRow li) {
		if (li == null) {
			return null;
		}
		String pl = li.resolvedPeriodLabel();
		if (pl != null && !pl.isBlank()) {
			return pl.trim();
		}
		if (li.getStartDate() != null && li.getEndDate() != null) {
			return li.getStartDate().toString() + "–" + li.getEndDate().toString();
		}
		return null;
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

	private List<QuotePromotionRow> readQuotePromotions(BillingQuoteLineItemsResponse quote) {
		if (quote == null || quote.getPromotions() == null || quote.getPromotions().isNull()) {
			return List.of();
		}
		return objectMapper.convertValue(quote.getPromotions(), new TypeReference<List<QuotePromotionRow>>() {
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

	private ScheduleAgg aggregateSchedule(List<QuoteLineItemRow> lines, LocalDate contractStart, LocalDate contractEnd) {
		if (lines.isEmpty()) {
			LocalDate today = LocalDate.now();
			LocalDate cs = nzDate(contractStart, today);
			LocalDate ce = nzDate(contractEnd, cs);
			String pl = trunc(formatIsoPeriodLabel(cs, ce), 100);
			return new ScheduleAgg("Billing", pl, cs, ce, today, 1, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
					BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);
		}
		BigDecimal sumPricePreTax = BigDecimal.ZERO;
		BigDecimal sumChargeAmount = BigDecimal.ZERO;
		BigDecimal fullCycleUnitNumerator = BigDecimal.ZERO;
		BigDecimal fullCycleBefDiscNumerator = BigDecimal.ZERO;
		BigDecimal tax = BigDecimal.ZERO;
		int qty = 0;
		LocalDate pStart = null;
		LocalDate pEnd = null;
		LocalDate billDate = null;
		for (QuoteLineItemRow li : lines) {
			if (li == null) {
				continue;
			}
			int q = nz(li.getQuantity(), 1);
			qty += q;
			if (li.getUnitPrice() != null) {
				fullCycleUnitNumerator = fullCycleUnitNumerator.add(li.getUnitPrice().multiply(BigDecimal.valueOf(q)));
			}
			if (li.getUnitPriceBeforeDiscount() != null) {
				fullCycleBefDiscNumerator = fullCycleBefDiscNumerator
						.add(li.getUnitPriceBeforeDiscount().multiply(BigDecimal.valueOf(q)));
			}
			if (li.getPrice() != null) {
				sumPricePreTax = sumPricePreTax.add(li.getPrice());
			}
			if (li.getAmount() != null) {
				sumChargeAmount = sumChargeAmount.add(li.getAmount());
			} else if (li.getPrice() != null) {
				sumChargeAmount = sumChargeAmount.add(li.getPrice());
			}
			if (li.getTax() != null) {
				tax = tax.add(li.getTax());
			}
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
		BigDecimal avgFullCycleUnit = qty > 0
				? fullCycleUnitNumerator.divide(BigDecimal.valueOf(qty), 2, RoundingMode.HALF_UP)
				: BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
		BigDecimal avgBefDisc = qty > 0 && fullCycleBefDiscNumerator.compareTo(BigDecimal.ZERO) > 0
				? fullCycleBefDiscNumerator.divide(BigDecimal.valueOf(qty), 2, RoundingMode.HALF_UP)
				: nzBd(first.getUnitPriceBeforeDiscount(), avgFullCycleUnit);
		BigDecimal subtotalBeforeTax = sumPricePreTax;
		if (subtotalBeforeTax.compareTo(BigDecimal.ZERO) == 0 && sumChargeAmount.compareTo(BigDecimal.ZERO) > 0) {
			subtotalBeforeTax = sumChargeAmount.subtract(tax).max(BigDecimal.ZERO).setScale(2, RoundingMode.HALF_UP);
		} else {
			subtotalBeforeTax = subtotalBeforeTax.setScale(2, RoundingMode.HALF_UP);
		}
		BigDecimal taxPct = first.getTaxPct();
		String label = first.getLabel() != null ? first.getLabel() : "Billing";
		String plFromLine = firstNonBlankLineField(lines, QuoteLineItemRow::resolvedPeriodLabel);
		String periodLabelCandidate = plFromLine != null ? plFromLine : formatIsoPeriodLabel(pStart, pEnd);
		ForecastBounds merged = resolveBillingPeriodBounds(new ForecastBounds(pStart, pEnd), periodLabelCandidate,
				contractStart, contractEnd);
		pStart = merged.periodStart();
		pEnd = merged.periodEnd();
		String periodLabelOut = plFromLine != null ? plFromLine : formatIsoPeriodLabel(pStart, pEnd);
		if (periodLabelOut == null) {
			periodLabelOut = formatIsoPeriodLabel(pStart, pEnd);
		}
		return new ScheduleAgg(label, trunc(periodLabelOut, 100), pStart, pEnd, billDate, qty, avgFullCycleUnit,
				avgBefDisc, sumChargeAmount.setScale(2, RoundingMode.HALF_UP), tax, taxPct, subtotalBeforeTax);
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
