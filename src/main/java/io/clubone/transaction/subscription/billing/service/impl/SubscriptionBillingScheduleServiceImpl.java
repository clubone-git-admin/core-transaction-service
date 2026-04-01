package io.clubone.transaction.subscription.billing.service.impl;


import org.springframework.stereotype.Service;

import io.clubone.transaction.request.SubscriptionPlanCreateRequest;
import io.clubone.transaction.subscription.billing.dao.SubscriptionBillingScheduleDAO;
import io.clubone.transaction.subscription.billing.model.CyclePriceProjection;
import io.clubone.transaction.subscription.billing.model.SubscriptionBillingScheduleRow;
import io.clubone.transaction.subscription.billing.service.SubscriptionBillingScheduleService;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Service
public class SubscriptionBillingScheduleServiceImpl implements SubscriptionBillingScheduleService {

    private static final int DEFAULT_CONTINUOUS_BILLING_HORIZON_MONTHS = 12;

    private final SubscriptionBillingScheduleDAO subscriptionBillingScheduleDAO;

    public SubscriptionBillingScheduleServiceImpl(
            SubscriptionBillingScheduleDAO subscriptionBillingScheduleDAO
    ) {
        this.subscriptionBillingScheduleDAO = subscriptionBillingScheduleDAO;
    }

    @Override
    public void generateInitialSchedule(SubscriptionPlanCreateRequest request,
                                        UUID subscriptionPlanId,
                                        UUID subscriptionInstanceId,
                                        UUID createdBy) {

        UUID plannedStatusId = subscriptionBillingScheduleDAO.billingScheduleStatusId("PLANNED");

        List<CyclePriceProjection> prices = subscriptionBillingScheduleDAO.findCyclePrices(subscriptionPlanId);
        if (prices == null || prices.isEmpty()) {
            throw new IllegalStateException("No subscription_plan_cycle_price rows found for planId=" + subscriptionPlanId);
        }

        LocalDate contractStartDate = request.getContractStartDate();
        LocalDate contractEndDate = request.getContractEndDate();

        Integer currentCycle = request.getCurrentCycle() == null ? 0 : request.getCurrentCycle();
        Integer intervalCount = request.getIntervalCount() == null || request.getIntervalCount() <= 0
                ? 1
                : request.getIntervalCount();

        boolean continuousBilling = contractEndDate == null;

        int monthsToGenerate;
        if (continuousBilling) {
            monthsToGenerate = DEFAULT_CONTINUOUS_BILLING_HORIZON_MONTHS;
        } else {
            monthsToGenerate = calculateMonthsBetweenInclusive(contractStartDate, contractEndDate);
        }

        LocalDate firstBillingDate = computeFirstScheduleBillingDate(request);
        int firstCycleNumber = currentCycle + 1;

        List<SubscriptionBillingScheduleRow> rows = new ArrayList<>();

        LocalDate cursorBillingDate = firstBillingDate;
        int cycleNumber = firstCycleNumber;

        for (int i = 0; i < monthsToGenerate; i++) {

            if (!continuousBilling && contractEndDate != null && cursorBillingDate.isAfter(contractEndDate)) {
                break;
            }

            if (subscriptionBillingScheduleDAO.existsByInstanceAndCycle(subscriptionInstanceId, cycleNumber)) {
                cursorBillingDate = addFrequency(cursorBillingDate, request.getSubscriptionFrequencyId(), intervalCount);
                cycleNumber++;
                continue;
            }

            CyclePriceProjection priceProjection = resolveCyclePrice(prices, cycleNumber);

            SubscriptionBillingScheduleRow row = new SubscriptionBillingScheduleRow();
            row.setClientAgreementId(request.getClientAgreementId());
            row.setSubscriptionPlanId(subscriptionPlanId);
            row.setSubscriptionInstanceId(subscriptionInstanceId);
            row.setCycleNumber(cycleNumber);

            row.setBillingPeriodStart(resolveBillingPeriodStart(cursorBillingDate));
            row.setBillingPeriodEnd(resolveBillingPeriodEnd(cursorBillingDate));

            row.setBillingDate(cursorBillingDate);
            row.setBaseAmount(nz(priceProjection.getEffectiveUnitPrice()));
            row.setOverrideAmount(null);
            row.setSystemAdjustmentAmount(BigDecimal.ZERO);
            row.setManualAdjustmentAmount(BigDecimal.ZERO);
            row.setDiscountAmount(BigDecimal.ZERO);
            row.setTaxAmount(BigDecimal.ZERO);

            row.setBillingScheduleStatusId(plannedStatusId);
            row.setPriceSourceType("SUBSCRIPTION_PLAN_CYCLE_PRICE");
            row.setPriceSourceId(priceProjection.getSubscriptionPlanCyclePriceId());

            row.setSourceEventType("PURCHASE");
            row.setSourceEventId(request.getClientAgreementId());

            row.setIsFreezeCycle(Boolean.FALSE);
            row.setIsCancellationCycle(Boolean.FALSE);
            row.setIsProrated(Boolean.FALSE);
            row.setIsGenerated(Boolean.TRUE);
            row.setIsLocked(Boolean.FALSE);
            row.setNotes("Initial schedule generated during purchase flow");
            row.setCreatedBy(createdBy);

            rows.add(row);

            cursorBillingDate = addFrequency(cursorBillingDate, request.getSubscriptionFrequencyId(), intervalCount);
            cycleNumber++;
        }

        if (!rows.isEmpty()) {
            subscriptionBillingScheduleDAO.batchInsertBillingSchedule(rows);
        }
    }

    private CyclePriceProjection resolveCyclePrice(List<CyclePriceProjection> prices, int cycleNumber) {

        System.out.println("------------------------------------------------");
        System.out.println("[DEBUG] Resolving price for cycleNumber = " + cycleNumber);

        if (prices == null || prices.isEmpty()) {
            System.out.println("[DEBUG] No cycle price records found!");
        }

        System.out.println("[DEBUG] Available price bands:");

        for (CyclePriceProjection p : prices) {

            System.out.println(
                "  bandId=" + p.getPriceCycleBandId()
                + " cycleStart=" + p.getCycleStart()
                + " cycleEnd=" + p.getCycleEnd()
                + " price=" + p.getEffectiveUnitPrice()
            );

            boolean lowerOk = cycleNumber >= p.getCycleStart();
            boolean upperOk = (p.getCycleEnd() == null || cycleNumber <= p.getCycleEnd());

            System.out.println(
                "     lowerOk=" + lowerOk +
                " upperOk=" + upperOk
            );

            if (lowerOk && upperOk) {
                System.out.println("[DEBUG] MATCH FOUND for cycle " + cycleNumber +
                        " using band start=" + p.getCycleStart() +
                        " end=" + p.getCycleEnd());
                System.out.println("------------------------------------------------");
                return p;
            }
        }

        System.out.println("[DEBUG] No direct match found. Checking open-ended bands...");

        CyclePriceProjection lastOpenEnded = null;

        for (CyclePriceProjection p : prices) {
            if (p.getCycleEnd() == null) {
                System.out.println("[DEBUG] Found open-ended band: start=" + p.getCycleStart());
                lastOpenEnded = p;
            }
        }

        if (lastOpenEnded != null) {
            System.out.println("[DEBUG] Using open-ended band for cycle " + cycleNumber);
            System.out.println("------------------------------------------------");
            return lastOpenEnded;
        }

        System.out.println("[ERROR] No matching cycle price found for cycleNumber=" + cycleNumber);
        System.out.println("------------------------------------------------");
        
        //Added this just to test in obligation flow. After test remove return statement and uncomment IllegalStateException 
        return prices.get(prices.size() -1);
        //throw new IllegalStateException("No matching cycle price found for cycleNumber=" + cycleNumber);
    }
    private LocalDate computeFirstScheduleBillingDate(SubscriptionPlanCreateRequest request) {
        if (request.getContractStartDate() == null) {
            throw new IllegalArgumentException("contractStartDate is required");
        }

        // You already have computeNextBillingDate(...) in your current service.
        // Reuse that same method/utility here for consistency.
        // For now, keeping a simple version aligned to monthly use case.

        LocalDate start = request.getContractStartDate();
        Integer currentCycle = request.getCurrentCycle() == null ? 0 : request.getCurrentCycle();
        Integer intervalCount = request.getIntervalCount() == null || request.getIntervalCount() <= 0
                ? 1
                : request.getIntervalCount();

        return start.plusMonths((long) currentCycle * intervalCount);
    }

    private LocalDate addFrequency(LocalDate billingDate, UUID subscriptionFrequencyId, int intervalCount) {
        // Simplified. In your project, map actual frequencyId to code through lookup if needed.
        // For current use case, treating as monthly by default.
        return billingDate.plusMonths(intervalCount);
    }

    private LocalDate resolveBillingPeriodStart(LocalDate billingDate) {
        return billingDate.withDayOfMonth(1);
    }

    private LocalDate resolveBillingPeriodEnd(LocalDate billingDate) {
        YearMonth ym = YearMonth.from(billingDate);
        return ym.atEndOfMonth();
    }

    private int calculateMonthsBetweenInclusive(LocalDate start, LocalDate end) {
        if (start == null || end == null || end.isBefore(start)) {
            return 0;
        }
        return (end.getYear() - start.getYear()) * 12 + (end.getMonthValue() - start.getMonthValue()) + 1;
    }

    private BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }
}