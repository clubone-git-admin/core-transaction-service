package io.clubone.transaction.service.impl;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.SQLException;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.BadSqlGrammarException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.clubone.transaction.dao.EntityLookupDao;
import io.clubone.transaction.dao.SubscriptionPlanDao;
import io.clubone.transaction.dao.SubscriptionPlanDao.BillingRule;
import io.clubone.transaction.dao.SubscriptionPromoBillingDAO;
import io.clubone.transaction.dao.TransactionDAO;
import io.clubone.transaction.dao.impl.SubscriptionInvoiceScheduleRow;
import io.clubone.transaction.request.SubscriptionPlanBatchCreateRequest;
import io.clubone.transaction.request.SubscriptionPlanCreateRequest;
import io.clubone.transaction.response.CreateInvoiceResponse;
import io.clubone.transaction.response.PlanCreateResult;
import io.clubone.transaction.response.SubscriptionPlanBatchCreateResponse;
import io.clubone.transaction.response.SubscriptionPlanCreateResponse;
import io.clubone.transaction.service.SubscriptionPlanService;
import io.clubone.transaction.service.TransactionServicev2;
import io.clubone.transaction.util.FrequencyUnit;
import io.clubone.transaction.v2.vo.CyclePriceDTO;
import io.clubone.transaction.v2.vo.EntitlementDTO;
import io.clubone.transaction.v2.vo.EntityLevelInfoDTO;
import io.clubone.transaction.v2.vo.InvoiceDetailDTO;
import io.clubone.transaction.v2.vo.InvoiceDetailRaw;
import io.clubone.transaction.v2.vo.PaymentTimelineItemDTO;
import io.clubone.transaction.v2.vo.SubscriptionPlanSummaryDTO;
import io.clubone.transaction.vo.TaxRateAllocationDTO;

@Service
public class SubscriptionPlanServiceImpl implements SubscriptionPlanService {

	@Autowired
	private SubscriptionPlanDao dao;

	@Autowired
	private PlatformTransactionManager txm;

	@Autowired
	private TransactionDAO transactionDAO;

	@Autowired
	private EntityLookupDao entityLookupDao;

	@Autowired
	private TransactionServicev2 tranServiceV2;

	@Autowired
	private SubscriptionPromoBillingDAO subscriptionPromoBillingDAO;

	private static final Logger log = LoggerFactory.getLogger(SubscriptionPlanServiceImpl.class);

	@Override
	@Transactional
	public SubscriptionPlanCreateResponse createPlanWithChildren(SubscriptionPlanCreateRequest request,
			UUID createdBy) {
		// 1) Insert plan
		UUID planId = dao.insertSubscriptionPlan(request, createdBy);
		ObjectMapper mapper = new ObjectMapper();
		try {
			System.out.println("Recod " + mapper.writeValueAsString(request.getCyclePrices()));
			System.out.println("ClientAgreementId " + request.getClientAgreementId());
		} catch (JsonProcessingException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
		// 2) Children (each optional, batched)
		int[] cpx = dao.batchInsertCyclePrices(planId, nz(request.getCyclePrices()), createdBy);
		int[] dcx = dao.batchInsertDiscountCodes(planId, nz(request.getDiscountCodes()), createdBy);
		int[] enx = dao.batchInsertEntitlements(planId, nz(request.getEntitlements()), createdBy);
		int[] pmx = dao.batchInsertPromos(planId, nz(request.getPromos()), createdBy);
		int term = dao.insertPlanTerm(planId, request.getTerm(), createdBy, request.getAgreementTermId(),
				request.getTotalCycles());
		// ✅ NEW: insert subscription_plan_promo rows (if present)
		if (request.getSubscriptionPlanPromos() != null && !request.getSubscriptionPlanPromos().isEmpty()) {
			subscriptionPromoBillingDAO.insertSubscriptionPlanPromos(planId, createdBy,
					request.getSubscriptionPlanPromos());
		}

		if (request.getTerm() != null && request.getCyclePrices() != null && !request.getCyclePrices().isEmpty()) {

			LocalDate start = request.getContractStartDate();
			LocalDate end = request.getContractEndDate();
			UUID freqId = request.getSubscriptionFrequencyId();
			Integer interval = request.getIntervalCount();
			UUID dayRuleId = request.getSubscriptionBillingDayRuleId();

			// ✅ these are the same values you store in billing history
			// Integer cycleNumber = request.getCyclePrices().get(0).getCycleStart(); //
			// e.g. 3
			Integer cycleNumber = request.getCurrentCycle();
			LocalDate billingDate = computeNextBillingDate(start, freqId, interval, dayRuleId, cycleNumber);
			cycleNumber = cycleNumber + 1;

			UUID priceCycleBandId = request.getCyclePrices().get(0).getPriceCycleBandId();

			UUID instanceStatusId = dao
					.subscriptionInstanceStatusId(start.isAfter(LocalDate.now()) ? "FUTURE" : "ACTIVE");
		//	UUID billingStatusScheduledId = dao.billingStatusId("PENDING");

			UUID instanceId = dao.insertSubscriptionInstance(planId, start, end, billingDate, instanceStatusId,
					createdBy, request.getCurrentCycle(), null);

			BigDecimal netExTax = firstCycleAmountOrZero(request);
			BigDecimal taxTotal = computeTaxesFromItemOnly(request.getEntityId(), request.getLevelId(), netExTax);
			BigDecimal grossInclTax = netExTax.add(taxTotal);

			long chargedMinor = 0; // keep as your current behavior

			// ✅ Create future invoice FIRST and store returned invoiceId in billing history
			UUID futureInvoiceId = null;

			// old invoice id = the invoice you are using as seed (from request)
			UUID seedInvoiceId = request.getInvoiceId();

			if (seedInvoiceId != null) {
				CreateInvoiceResponse futureInvResp = tranServiceV2.createFutureInvoice(seedInvoiceId, cycleNumber,
						billingDate, createdBy, request.getClientAgreementId());

				futureInvoiceId = futureInvResp.getInvoiceId(); // ✅ use new invoice id
				System.out.println(
						"[SUBSCRIPTION] Future invoice created: seedInvoiceId=" + seedInvoiceId + " cycleNumber="
								+ cycleNumber + " billingDate=" + billingDate + " newInvoiceId=" + futureInvoiceId);
			} else {
				System.out.println("[SUBSCRIPTION] seedInvoiceId is NULL - skipping createFutureInvoice()");
			}

			/*
			 * SubscriptionPlanDao.BillingHistoryRow row = new
			 * SubscriptionPlanDao.BillingHistoryRow( instanceId, chargedMinor, cycleNumber,
			 * // ✅ cycle number stored in history billingDate, // ✅ billing date stored in
			 * history billingStatusScheduledId, priceCycleBandId, null, null,
			 * futureInvoiceId, // ✅ store the NEW invoiceId (not the seed one) netExTax,
			 * taxTotal, null, null, null, null, Boolean.FALSE, null, null, null, null );
			 * 
			 * UUID historyId = dao.insertSubscriptionBillingHistoryReturningId(row);
			 */

			SubscriptionInvoiceScheduleRow row = new SubscriptionInvoiceScheduleRow(futureInvoiceId, // invoiceId
					instanceId, // subscriptionInstanceId
					cycleNumber, // cycleNumber
					billingDate, // paymentDueDate
					createdBy // createdBy (optional)
			);

			UUID scheduleId = dao.insertSubscriptionInvoiceScheduleReturningId(row);

			// ✅ NEW: insert subscription_billing_promotion rows (if present)
			if (request.getSubscriptionBillingPromotions() != null
					&& !request.getSubscriptionBillingPromotions().isEmpty()) {
				subscriptionPromoBillingDAO.insertSubscriptionBillingPromotions(scheduleId,
						request.getSubscriptionBillingPromotions());
			}

		}

		// Build response
		SubscriptionPlanCreateResponse resp = new SubscriptionPlanCreateResponse();
		resp.setSubscriptionPlanId(planId);
		resp.setCyclePricesInserted(cpx.length);
		resp.setDiscountCodesInserted(dcx.length);
		resp.setEntitlementsInserted(enx.length);
		resp.setPromosInserted(pmx.length);
		resp.setTermInserted(term > 0);
		return resp;
	}

	/*
	 * @Override
	 * 
	 * @Transactional public SubscriptionPlanCreateResponse
	 * createPlanWithChildren(SubscriptionPlanCreateRequest request, UUID createdBy)
	 * { // 1) Insert plan UUID planId = dao.insertSubscriptionPlan(request,
	 * createdBy); ObjectMapper mapper=new ObjectMapper(); try {
	 * System.out.println("Recod "+mapper.writeValueAsString(request.getCyclePrices(
	 * ))); System.out.println("ClientAgreementId "+request.getClientAgreementId());
	 * } catch (JsonProcessingException e) { // TODO Auto-generated catch block
	 * e.printStackTrace(); } // 2) Children (each optional, batched) int[] cpx =
	 * dao.batchInsertCyclePrices(planId, nz(request.getCyclePrices()), createdBy);
	 * int[] dcx = dao.batchInsertDiscountCodes(planId,
	 * nz(request.getDiscountCodes()), createdBy); int[] enx =
	 * dao.batchInsertEntitlements(planId, nz(request.getEntitlements()),
	 * createdBy); int[] pmx = dao.batchInsertPromos(planId,
	 * nz(request.getPromos()), createdBy); int term = dao.insertPlanTerm(planId,
	 * request.getTerm(),
	 * createdBy,request.getAgreementTermId(),request.getTotalCycles());
	 * 
	 * if (request.getTerm() != null && request.getCyclePrices() != null &&
	 * !request.getCyclePrices().isEmpty()) {
	 * 
	 * LocalDate start = request.getContractStartDate(); LocalDate end =
	 * request.getContractEndDate(); UUID freqId =
	 * request.getSubscriptionFrequencyId(); Integer interval =
	 * request.getIntervalCount(); UUID dayRuleId =
	 * request.getSubscriptionBillingDayRuleId();
	 * 
	 * // ✅ these are the same values you store in billing history //Integer
	 * cycleNumber = request.getCyclePrices().get(0).getCycleStart(); // e.g. 3
	 * Integer cycleNumber=request.getCurrentCycle()+1; LocalDate billingDate =
	 * computeNextBillingDate(start, freqId, interval, dayRuleId, cycleNumber);
	 * 
	 * UUID priceCycleBandId =
	 * request.getCyclePrices().get(0).getPriceCycleBandId();
	 * 
	 * UUID instanceStatusId =
	 * dao.subscriptionInstanceStatusId(start.isAfter(LocalDate.now()) ? "FUTURE" :
	 * "ACTIVE"); UUID billingStatusScheduledId = dao.billingStatusId("PENDING");
	 * 
	 * UUID instanceId = dao.insertSubscriptionInstance( planId, start, end,
	 * billingDate, instanceStatusId, createdBy, request.getCurrentCycle(), null );
	 * 
	 * BigDecimal netExTax = firstCycleAmountOrZero(request); BigDecimal taxTotal =
	 * computeTaxesFromItemOnly(request.getEntityId(), request.getLevelId(),
	 * netExTax); BigDecimal grossInclTax = netExTax.add(taxTotal);
	 * 
	 * long chargedMinor = 0; // keep as your current behavior
	 * 
	 * // ✅ Create future invoice FIRST and store returned invoiceId in billing
	 * history UUID futureInvoiceId = null;
	 * 
	 * // old invoice id = the invoice you are using as seed (from request) UUID
	 * seedInvoiceId = request.getInvoiceId();
	 * 
	 * if (seedInvoiceId != null) { CreateInvoiceResponse futureInvResp =
	 * tranServiceV2.createFutureInvoice(seedInvoiceId, cycleNumber, billingDate,
	 * createdBy,request.getClientAgreementId());
	 * 
	 * futureInvoiceId = futureInvResp.getInvoiceId(); // ✅ use new invoice id
	 * System.out.println("[SUBSCRIPTION] Future invoice created: seedInvoiceId=" +
	 * seedInvoiceId + " cycleNumber=" + cycleNumber + " billingDate=" + billingDate
	 * + " newInvoiceId=" + futureInvoiceId); } else { System.out.
	 * println("[SUBSCRIPTION] seedInvoiceId is NULL - skipping createFutureInvoice()"
	 * ); }
	 * 
	 * SubscriptionPlanDao.BillingHistoryRow row = new
	 * SubscriptionPlanDao.BillingHistoryRow( instanceId, chargedMinor, cycleNumber,
	 * // ✅ cycle number stored in history billingDate, // ✅ billing date stored in
	 * history billingStatusScheduledId, priceCycleBandId, null, null,
	 * futureInvoiceId, // ✅ store the NEW invoiceId (not the seed one) netExTax,
	 * taxTotal, null, null, null, null, Boolean.FALSE, null, null, null, null );
	 * 
	 * dao.insertSubscriptionBillingHistory(row);
	 * subscriptionPromoBillingDAO.insertSubscriptionBillingPromotions(
	 * seedInvoiceId, null) }
	 * 
	 * 
	 * // Build response SubscriptionPlanCreateResponse resp = new
	 * SubscriptionPlanCreateResponse(); resp.setSubscriptionPlanId(planId);
	 * resp.setCyclePricesInserted(cpx.length);
	 * resp.setDiscountCodesInserted(dcx.length);
	 * resp.setEntitlementsInserted(enx.length); resp.setPromosInserted(pmx.length);
	 * resp.setTermInserted(term > 0); return resp; }
	 */

	private BigDecimal computeTaxesFromItemOnly(UUID itemId, UUID levelId, BigDecimal unitPrice) {
		UUID taxGroupId = null;
		BigDecimal taxAmount = BigDecimal.ZERO;
		try {
			taxGroupId = transactionDAO.findTaxGroupIdForItem(itemId, levelId);
		} catch (Exception ignore) {
		}

		if (taxGroupId == null) {
			return taxAmount;
		}

		List<TaxRateAllocationDTO> taxAllocs = transactionDAO.getTaxRatesByGroupAndLevel(taxGroupId, levelId);

		if (taxAllocs == null || taxAllocs.isEmpty()) {
			return taxAmount;
		}
		for (TaxRateAllocationDTO tr : taxAllocs) {
			BigDecimal thisTax = unitPrice.multiply(tr.getTaxRatePercentage()).divide(new BigDecimal("100"), 2,
					RoundingMode.HALF_UP);

			taxAmount = taxAmount.add(thisTax);
		}
		return taxAmount;
	}

	private static <T> List<T> nz(List<T> list) {
		return list == null ? java.util.List.of() : list;
	}

	@Override
	public SubscriptionPlanBatchCreateResponse createPlans(SubscriptionPlanBatchCreateRequest batchReq,
			UUID createdBy) {
		boolean atomic = batchReq.getAtomic() == null || batchReq.getAtomic();
		List<PlanCreateResult> results = new ArrayList<>();

		if (atomic) {
			return new TransactionTemplate(txm).execute(status -> {
				int ok = 0;
				for (var plan : batchReq.getPlans()) {
					try {
						var detail = createPlanWithChildren(plan, createdBy);
						results.add(success(detail));
						ok++;
					} catch (DataAccessException ex) {
						// ✅ print actual DB error + SQL
						Throwable root = ex.getMostSpecificCause();
						if (root instanceof SQLException sql) {
							String badSql = (ex instanceof BadSqlGrammarException b) ? b.getSql() : "<n/a>";
							log.error("DB error. state={}, code={}, msg={}, sql={}", sql.getSQLState(),
									sql.getErrorCode(), sql.getMessage(), badSql, ex);
						} else {
							log.error("DB error: {}", root != null ? root.getMessage() : ex.getMessage(), ex);
						}
						status.setRollbackOnly();
						results.add(fail(ex)); // consider using root.getMessage() here
						break;
					} catch (Exception ex) {
						log.error("Unexpected error creating plan", ex);
						status.setRollbackOnly();
						results.add(fail(ex));
						break;
					}
				}
				var resp = new SubscriptionPlanBatchCreateResponse();
				resp.setAtomic(true);
				resp.setTotalRequested(batchReq.getPlans().size());
				resp.setTotalSucceeded(ok);
				resp.setTotalFailed(batchReq.getPlans().size() - ok);
				resp.setResults(results);
				return resp;
			});
		} else {
			int ok = 0, fail = 0;
			for (var plan : batchReq.getPlans()) {
				try {
					var detail = new TransactionTemplate(txm).execute(tx -> createPlanWithChildren(plan, createdBy));
					results.add(success(detail));
					ok++;
				} catch (DataAccessException ex) {
					Throwable root = ex.getMostSpecificCause();
					if (root instanceof SQLException sql) {
						String badSql = (ex instanceof BadSqlGrammarException b) ? b.getSql() : "<n/a>";
						log.error("[Per-plan] DB error. state={}, code={}, msg={}, sql={}", sql.getSQLState(),
								sql.getErrorCode(), sql.getMessage(), badSql, ex);
					} else {
						log.error("[Per-plan] DB error: {}", root != null ? root.getMessage() : ex.getMessage(), ex);
					}
					results.add(fail(ex));
					fail++;
				} catch (Exception ex) {
					log.error("[Per-plan] Unexpected error creating plan", ex);
					results.add(fail(ex));
					fail++;
				}
			}
			var resp = new SubscriptionPlanBatchCreateResponse();
			resp.setAtomic(false);
			resp.setTotalRequested(batchReq.getPlans().size());
			resp.setTotalSucceeded(ok);
			resp.setTotalFailed(fail);
			resp.setResults(results);
			return resp;
		}
	}

	private static PlanCreateResult success(SubscriptionPlanCreateResponse detail) {
		PlanCreateResult r = new PlanCreateResult();
		r.setSuccess(true);
		r.setSubscriptionPlanId(detail.getSubscriptionPlanId());
		r.setMessage("Created");
		r.setDetail(detail);
		return r;
	}

	private static PlanCreateResult fail(Exception ex) {
		PlanCreateResult r = new PlanCreateResult();
		r.setSuccess(false);
		r.setMessage(ex.getMessage());
		r.setDetail(null);
		return r;
	}

	/**
	 * Very simple starting point. Replace with your real rules: - SAME_DAY_AS_START
	 * -> start - FIXED_DAY_OF_MONTH -> adjust to that day in start month - etc.,
	 * then advance by interval/frequency when needed.
	 * 
	 * @param cycleStart
	 */
	public LocalDate computeNextBillingDate(LocalDate start, UUID frequencyId, Integer interval, UUID dayRuleId,
			Integer cycleStart) {
		cycleStart = cycleStart - 1;

		if (start == null)
			start = LocalDate.now();
		int effInterval = (interval != null && interval > 0) ? interval : 1;
		int skip = Math.max(0, (cycleStart == null ? 1 : cycleStart) - 1); // skip (cycleStart-1)

		BillingRule rule = dao.findRule(frequencyId, dayRuleId)
				.orElseThrow(() -> new IllegalArgumentException("Invalid frequency/day rule"));

		String freq = rule.frequencyName() == null ? "" : rule.frequencyName().trim().toUpperCase();
		String billingDay = rule.billingDay() == null ? "" : rule.billingDay().trim();

// Move forward by 'skip' * interval units
		LocalDate anchor = switch (freq) {
		case "DAILY" -> start.plusDays((long) skip * effInterval);
		case "WEEKLY" -> start.plusWeeks((long) skip * effInterval);
		case "MONTHLY" -> start.plusMonths((long) skip * effInterval);
		case "QUARTERLY" -> start.plusMonths((long) skip * effInterval * 3L);
		case "YEARLY" -> start.plusYears((long) skip * effInterval);
		default -> start.plusMonths((long) skip * effInterval); // sensible default
		};

// Align to billing day according to frequency
		return alignToBillingDay(anchor, freq, billingDay);
	}

	private LocalDate alignToBillingDay(LocalDate anchor, String freq, String billingDay) {
		switch (freq) {
		case "DAILY":
// No special alignment; "next" is the anchor itself for daily cadence
			return anchor;

		case "WEEKLY": {
			DayOfWeek target = parseDayOfWeek(billingDay);
// If anchor already on target DoW, keep it; else move forward to the next target DoW
			int diff = (target.getValue() - anchor.getDayOfWeek().getValue() + 7) % 7;
			return diff == 0 ? anchor : anchor.plusDays(diff);
		}

		case "MONTHLY":
		case "QUARTERLY":
		case "YEARLY": {
// For MONTHLY/QUARTERLY: billing_day usually "1".."31" or "LAST"
// For YEARLY: support "MM-DD" (e.g., "03-15"). If plain number, treat like monthly day-of-month.
			if (billingDay.equalsIgnoreCase("LAST") || billingDay.equalsIgnoreCase("LAST_DAY")) {
				return anchor.withDayOfMonth(anchor.lengthOfMonth());
			}
			if (billingDay.matches("\\d{1,2}-\\d{1,2}")) { // YEARLY like "MM-DD"
				String[] parts = billingDay.split("-");
				int mm = Integer.parseInt(parts[0]);
				int dd = Integer.parseInt(parts[1]);
// set same year as anchor; clamp day to month length
				int safeDay = Math.min(dd, YearMonth.of(anchor.getYear(), mm).lengthOfMonth());
				LocalDate candidate = LocalDate.of(anchor.getYear(), mm, safeDay);
// if candidate before anchor, push to next year
				return !candidate.isBefore(anchor) ? candidate
						: LocalDate.of(anchor.getYear() + 1, mm,
								Math.min(dd, YearMonth.of(anchor.getYear() + 1, mm).lengthOfMonth()));
			}
// else numeric day-of-month
			int day = parseIntSafe(billingDay, anchor.getDayOfMonth());
			int dom = Math.min(day, anchor.lengthOfMonth());
// If moving to same month's DOM yields a date before anchor (e.g., anchor is 25th, billing day 15),
// treat current anchor as already past; move to next period and set DOM again.
			LocalDate candidate = anchor.withDayOfMonth(dom);
			if (candidate.isBefore(anchor)) {
// advance one period matching freq granularity
				candidate = switch (freq) {
				case "MONTHLY" ->
					anchor.plusMonths(1).withDayOfMonth(Math.min(day, anchor.plusMonths(1).lengthOfMonth()));
				case "QUARTERLY" ->
					anchor.plusMonths(3).withDayOfMonth(Math.min(day, anchor.plusMonths(3).lengthOfMonth()));
				case "YEARLY" -> anchor.plusYears(1).withDayOfMonth(Math.min(day, anchor.plusYears(1).lengthOfMonth())); // rarely
																															// used
				default -> candidate;
				};
			}
			return candidate;
		}

		default:
			return anchor;
		}
	}

	private static DayOfWeek parseDayOfWeek(String s) {
		if (s == null || s.isBlank())
			return DayOfWeek.MONDAY;
		String u = s.trim().toUpperCase(Locale.ROOT);
// Accept "MON", "MONDAY", etc.
		switch (u) {
		case "MON":
		case "MONDAY":
			return DayOfWeek.MONDAY;
		case "TUE":
		case "TUESDAY":
			return DayOfWeek.TUESDAY;
		case "WED":
		case "WEDNESDAY":
			return DayOfWeek.WEDNESDAY;
		case "THU":
		case "THURSDAY":
			return DayOfWeek.THURSDAY;
		case "FRI":
		case "FRIDAY":
			return DayOfWeek.FRIDAY;
		case "SAT":
		case "SATURDAY":
			return DayOfWeek.SATURDAY;
		case "SUN":
		case "SUNDAY":
			return DayOfWeek.SUNDAY;
		default:
			return DayOfWeek.MONDAY;
		}
	}

	private static int parseIntSafe(String s, int fallback) {
		try {
			return Integer.parseInt(s.trim());
		} catch (Exception e) {
			return fallback;
		}
	}

	/**
	 * Computes net amount excl tax for the first billing cycle: - base = effective
	 * unit price for that cycle - qty = entitlement quantity per cycle (fallback 1)
	 * - discount = sum of subscription billing promotions (fallback 0) - result
	 * clamped to >= 0
	 */
	private BigDecimal firstCycleAmountOrZero(SubscriptionPlanCreateRequest req) {
		if (req == null || req.getCyclePrices() == null || req.getCyclePrices().isEmpty()) {
			return BigDecimal.ZERO;
		}

		// which cycle are we pricing for?
		Integer cycle = req.getCyclePrices().get(0).getCycleStart();
		if (cycle == null)
			cycle = 1;

		// 1) base unit price for this cycle
		BigDecimal unitPrice = effectiveUnitPriceForCycle(req.getCyclePrices(), cycle).orElse(BigDecimal.ZERO);

		// 2) quantity (use entitlement quantityPerCycle if available; else 1)
		int qty = resolveEntitlementQty(req);

		BigDecimal base = unitPrice.multiply(BigDecimal.valueOf(qty));

		// 3) promotion/discount applied (sum)
		BigDecimal promoDiscount = resolvePromoDiscount(req);

		BigDecimal net = base.subtract(promoDiscount);

		// 4) clamp to 0 (no negative billing)
		if (net.signum() < 0)
			return BigDecimal.ZERO;

		return net;
	}

	private int resolveEntitlementQty(SubscriptionPlanCreateRequest req) {
		if (req.getEntitlements() == null || req.getEntitlements().isEmpty())
			return 1;

		boolean unlimited = req.getEntitlements().stream()
				.anyMatch(e -> e != null && Boolean.TRUE.equals(e.getIsUnlimited()));
		if (unlimited)
			return 1;

		return req.getEntitlements().stream().filter(e -> e != null).map(EntitlementDTO::getQuantityPerCycle)
				.filter(q -> q != null && q > 0).max(Integer::compareTo).orElse(1);
	}

	private BigDecimal resolvePromoDiscount(SubscriptionPlanCreateRequest req) {
		BigDecimal total = BigDecimal.ZERO;

		// Preferred: subscription billing promotions (already computed per cycle)
		if (req.getSubscriptionBillingPromotions() != null && !req.getSubscriptionBillingPromotions().isEmpty()) {
			for (var p : req.getSubscriptionBillingPromotions()) {
				if (p == null)
					continue;
				if (p.getAmountApplied() != null) {
					total = total.add(p.getAmountApplied());
				}
			}
			return total;
		}

		// Fallback: your existing "promos" list if you want to support it here too
		if (req.getPromos() != null && !req.getPromos().isEmpty()) {
			for (var p : req.getPromos()) {
				if (p == null)
					continue;
				// adjust this getter based on your PromoDTO fields
				// total = total.add(nz(p.getAmountApplied()));
			}
		}

		return total;
	}

	/**
	 * Returns the effective unit price for the cycle: - picks the price row whose
	 * [cycleStart, cycleEnd] contains `cycle` - if multiple match, prefers the one
	 * with the highest cycleStart - uses windowOverrideUnitPrice when present; else
	 * unitPrice
	 */
	private Optional<BigDecimal> effectiveUnitPriceForCycle(List<CyclePriceDTO> prices, int cycle) {
		if (prices == null || prices.isEmpty())
			return Optional.empty();

		return prices.stream()
				.filter(p -> p != null && p.getCycleStart() != null && p.getCycleStart() <= cycle
						&& (p.getCycleEnd() == null || p.getCycleEnd() >= cycle))
				// if multiple bands match, take the most specific (largest cycleStart)
				.sorted(Comparator.<CyclePriceDTO, Integer>comparing(
						p -> p.getCycleStart() == null ? Integer.MIN_VALUE : p.getCycleStart()).reversed())
				.map(p -> {
					BigDecimal eff = p.getWindowOverrideUnitPrice();
					if (eff == null)
						eff = p.getUnitPrice();
					return Optional.ofNullable(eff);
				}).filter(Optional::isPresent).map(Optional::get).findFirst();
	}

	@Override
	@Transactional(readOnly = true)
	public InvoiceDetailDTO getSubscriptionDetail(UUID subscriptionPlanId) {
		final InvoiceDetailRaw raw = dao.loadInvoiceAggregateBySubscriptionPlan(subscriptionPlanId).orElseThrow(
				() -> new NoSuchElementException("No invoice found for subscriptionPlanId: " + subscriptionPlanId));

		return mapRawToDto(raw);
	}

	private InvoiceDetailDTO mapRawToDto(InvoiceDetailRaw raw) {
		final int currentCycle = nvl(raw.currentCycleNumber(), 0);
		final int totalCycle = nvl(raw.totalCycles(), 0);

		// Prefer SBH gross incl. tax if present; fallback to invoice header amount
		final BigDecimal priceForCurrentCycle = raw.amountGrossInclTax() != null ? raw.amountGrossInclTax()
				: raw.invoiceAmount();

		// Build frequency label
		final FrequencyUnit unit = FrequencyUnit.fromDb(raw.frequencyName());
		final int interval = nvl(raw.intervalCount(), 1);
		final LocalDate refDate = coalesce(raw.instanceNextBillingDate(),
				coalesce(raw.instanceLastBilledOn(), raw.instanceStartDate()));
		final String billingFrequencyLabel = switch (unit) {
		case MONTH -> {
			int dom = (refDate != null ? refDate.getDayOfMonth() : 1);
			yield "Monthly on the " + dom + getDaySuffix(dom);
		}
		case WEEK -> "Every " + interval + (interval == 1 ? " week" : " weeks");
		case YEAR -> "Yearly";
		case DAY -> "Daily";
		};

		// Commitment paid (current / total)
		Integer numerator = (raw.remainingCycles() == null || totalCycle == 0) ? null
				: Math.max(0, totalCycle - raw.remainingCycles());
		Integer denominator = (totalCycle == 0 ? null : totalCycle);

		// Build timeline (reuses your existing helper)
		final List<PaymentTimelineItemDTO> timeline = buildTimeline(unit, interval, raw, priceForCurrentCycle);

		// Resolve display entity (prefer parent; fallback to child) — FIXED bug:
		// fallback should use childEntityId()
		final UUID parentEntityId = raw.parentEntityId() != null ? raw.parentEntityId() : raw.childEntityId();
		final UUID parentEntityTypeId = raw.parentEntityTypeId() != null ? raw.parentEntityTypeId()
				: raw.childEntityTypeId();
		final UUID levelId = raw.levelId();

		String entityName = "";
		String locationName = "";
		entityLookupDao.resolveEntityAndLevel(parentEntityTypeId, parentEntityId, levelId).ifPresent(info -> {
			// small holder to mutate effectively final vars
		});

		// Workaround because lambdas need effectively final; fetch once and assign
		Optional<EntityLevelInfoDTO> entityDetail = entityLookupDao.resolveEntityAndLevel(parentEntityTypeId,
				parentEntityId, levelId);
		if (entityDetail.isPresent()) {
			entityName = entityDetail.get().entityName();
			locationName = entityDetail.get().levelName();
		}

		return new InvoiceDetailDTO(raw.invoiceId(), raw.invoiceNumber(), raw.invoiceDate(), raw.invoiceStatus(),
				raw.invoiceAmount(), raw.invoiceBalanceDue(), raw.invoiceWriteOff(), raw.salesRep(),

				// badges / header chips (customize as needed)
				"CONTRACT", "ACTIVE", entityName, locationName, priceForCurrentCycle,

				// commitment progress
				numerator, denominator, billingFrequencyLabel, raw.contractEndDate(), raw.instanceNextBillingDate(),
				raw.instanceStartDate(), raw.contractStartDate(), // using contract start as sign-up for now

				null, // autoPay (TODO: fetch from payment settings)
				null, // primaryPaymentMethodMasked (TODO)

				"Membership · Base Membership", // TODO derive from plan/entity
				true, // recurring
				"—", null,

				timeline);
	}

	private static <T> T nvl(T v, T fallback) {
		return v != null ? v : fallback;
	}

	private static <T> T coalesce(T a, T b) {
		return a != null ? a : b;
	}

	private static List<PaymentTimelineItemDTO> buildTimeline(FrequencyUnit unit, int interval, InvoiceDetailRaw raw,
			BigDecimal cycleAmount) {
		List<PaymentTimelineItemDTO> list = new ArrayList<>();
		list.add(new PaymentTimelineItemDTO(raw.invoiceDate(), raw.invoiceAmount(),
				(raw.invoiceStatus().equalsIgnoreCase("PAID")) ? true : false));

		LocalDate paidDate = raw.instanceLastBilledOn();
		if (paidDate != null) {
			list.add(new PaymentTimelineItemDTO(paidDate, cycleAmount, true));
		}

		LocalDate start = Optional.ofNullable(raw.instanceNextBillingDate())
				.orElseGet(() -> paidDate != null ? addCycles(paidDate, unit, interval, 1) : raw.instanceStartDate());

		LocalDate d = start;
		for (int i = 0; i < raw.remainingCycles() - (paidDate != null ? 1 : 0); i++) {
			list.add(new PaymentTimelineItemDTO(d, cycleAmount, false));
			d = addCycles(d, unit, interval, 1);
			if (raw.instanceEndDate() != null && d.isAfter(raw.instanceEndDate()))
				break;
		}
		return list;
	}

	private static LocalDate addCycles(LocalDate date, FrequencyUnit unit, int interval, int count) {
		int steps = Math.max(1, interval) * Math.max(1, count);
		return switch (unit) {
		case DAY -> date.plusDays(steps);
		case WEEK -> date.plusWeeks(steps);
		case MONTH -> date.plusMonths(steps);
		case YEAR -> date.plusYears(steps);
		};
	}

	private static String getDaySuffix(int day) {
		if (day >= 11 && day <= 13)
			return "th";
		return switch (day % 10) {
		case 1 -> "st";
		case 2 -> "nd";
		case 3 -> "rd";
		default -> "th";
		};
	}

	@Override
	@Transactional(readOnly = true)
	public List<SubscriptionPlanSummaryDTO> getClientSubscriptionPlans(UUID clientRoleId) {
		return dao.findClientSubscriptionPlans(clientRoleId);
	}
}
