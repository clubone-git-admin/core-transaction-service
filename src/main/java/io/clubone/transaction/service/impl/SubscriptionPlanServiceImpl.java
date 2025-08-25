package io.clubone.transaction.service.impl;

import io.clubone.transaction.dao.SubscriptionPlanDao;
import io.clubone.transaction.request.SubscriptionPlanBatchCreateRequest;
import io.clubone.transaction.request.SubscriptionPlanCreateRequest;
import io.clubone.transaction.response.PlanCreateResult;
import io.clubone.transaction.response.SubscriptionPlanBatchCreateResponse;
import io.clubone.transaction.response.SubscriptionPlanCreateResponse;
import io.clubone.transaction.service.SubscriptionPlanService;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.BadSqlGrammarException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Service
public class SubscriptionPlanServiceImpl implements SubscriptionPlanService {

	@Autowired
	private SubscriptionPlanDao dao;

	@Autowired
	private PlatformTransactionManager txm;

	private static final Logger log = LoggerFactory.getLogger(SubscriptionPlanServiceImpl.class);

	@Override
	// @Transactional
	public SubscriptionPlanCreateResponse createPlanWithChildren(SubscriptionPlanCreateRequest request,
			UUID createdBy) {
		// 1) Insert plan
		UUID planId = dao.insertSubscriptionPlan(request, createdBy);

		// 2) Children (each optional, batched)
		int[] cpx = dao.batchInsertCyclePrices(planId, nz(request.getCyclePrices()), createdBy);
		int[] dcx = dao.batchInsertDiscountCodes(planId, nz(request.getDiscountCodes()), createdBy);
		int[] enx = dao.batchInsertEntitlements(planId, nz(request.getEntitlements()), createdBy);
		int[] pmx = dao.batchInsertPromos(planId, nz(request.getPromos()), createdBy);
		int term = dao.insertPlanTerm(planId, request.getTerm(), createdBy);

		if (request.getTerm() != null) {
			LocalDate start = request.getContractStartDate();
			LocalDate end = request.getContractEndDate();
			UUID freqId = request.getSubscriptionFrequencyId(); // daily/weekly/monthly...
			Integer interval = request.getIntervalCount(); // e.g., 1, 3, 12
			UUID dayRuleId = request.getSubscriptionBillingDayRuleId();

			LocalDate nextBill = computeNextBillingDate(start, freqId, interval, dayRuleId);

			// Resolve status IDs for instance + billing
			UUID instanceStatusId = dao
					.subscriptionInstanceStatusId(start.isAfter(LocalDate.now()) ? "FUTURE" : "ACTIVE");
			UUID billingStatusScheduledId = dao.billingStatusId("PENDING");

			// Create instance (currentCycleNumber = 0, lastBilledOn = null)
			UUID instanceId = dao.insertSubscriptionInstance(planId, start, end, nextBill, instanceStatusId, createdBy,
					0, null);

			// OPTIONAL: seed first billing history row (cycle 1).
			// If you don’t have final pricing yet, seed minimal required values and let
			// the billing engine update it later.
			BigDecimal grossInclTax = firstCycleAmountOrZero(request); // put your pricing calc here
			BigDecimal taxTotal = BigDecimal.ZERO; // fill when you have tax
			BigDecimal netExTax = grossInclTax.subtract(taxTotal);

			// Convert to minor units (INR/USD -> 2 decimals)
			long chargedMinor = grossInclTax.movePointRight(2).setScale(0, RoundingMode.HALF_UP).longValueExact();

			SubscriptionPlanDao.BillingHistoryRow row = new SubscriptionPlanDao.BillingHistoryRow(instanceId,
					chargedMinor, 1, nextBill, billingStatusScheduledId, null, // set if you know current band
					null, null, null, netExTax, taxTotal, null, null, null, null, Boolean.FALSE, null, null, null,
					null);
			dao.insertSubscriptionBillingHistory(row);
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
	 */
	private LocalDate computeNextBillingDate(LocalDate start, UUID frequencyId, Integer interval, UUID dayRuleId) {
		// TODO: decide by your lookup/enum values behind frequencyId/dayRuleId
		return start;
	}

	/** Placeholder: derive first cycle amount. Replace with your pricing engine. */
	private BigDecimal firstCycleAmountOrZero(SubscriptionPlanCreateRequest req) {
		// e.g., read first cycle price from req.getCyclePrices(), apply discounts,
		// taxes
		return BigDecimal.ZERO;
	}
}
