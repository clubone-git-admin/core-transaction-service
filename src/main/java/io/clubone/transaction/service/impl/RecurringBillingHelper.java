package io.clubone.transaction.service.impl;


import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import io.clubone.transaction.dao.SubscriptionBillingDAO;
import io.clubone.transaction.vo.SubscriptionBillingHistoryDTO;
import io.clubone.transaction.vo.SubscriptionInstanceDTO;
import io.clubone.transaction.vo.SubscriptionPlanDTO;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

@Component
@RequiredArgsConstructor
public class RecurringBillingHelper {

    private final SubscriptionBillingDAO susBillingDAO;

    /**
     * Sets up recurring billing (plan + instance + first history) if conditions are met.
     *
     * @param isContinuous        true to enable recurring
     * @param recurrenceCount     number of billings (e.g., 12); you should track the count in your scheduler via history rows
     * @param entityId            client_agreement_id (or whatever entity_id your plan uses)
     * @param entityTypeId        FK -> transaction.lu_entity_type (e.g., "Item" or "Bundle")
     * @param clientPaymentMethodId payment method to charge
     * @param amount              amount per cycle
     * @param subscriptionFrequencyId FK -> lu_subscription_frequency
     * @param intervalCount       e.g., 1 (every month), 2 (every 2 months)
     * @param subscriptionBillingDayRuleId FK -> lu_subscription_billing_day_rule (may define day-of-month/weekday)
     * @param invoiceId           the invoice created for the first charge
     * @param createdBy           user performing the operation
     * @param invoiceDate         when the first billing occurs
     * @return subscriptionInstanceId if created, otherwise null
     */
    @Transactional
    public UUID setupRecurringIfNeeded(
            Boolean isContinuous,
            Integer recurrenceCount,
            UUID entityId,
            UUID entityTypeId,
            UUID clientPaymentMethodId,
            BigDecimal amount,
            UUID subscriptionFrequencyId,
            Integer intervalCount,
            UUID subscriptionBillingDayRuleId,
            UUID invoiceId,
            UUID createdBy,
            LocalDate invoiceDate
    ) {
        if (!Boolean.TRUE.equals(isContinuous) || recurrenceCount == null || recurrenceCount <= 1) {
            return null; // no recurring setup needed
        }

        // 1) Build and insert plan
        SubscriptionPlanDTO plan = new SubscriptionPlanDTO();
        plan.setEntityId(entityId);
        plan.setClientPaymentMethodId(clientPaymentMethodId);
        plan.setAmount(amount);
        plan.setSubscriptionFrequencyId(subscriptionFrequencyId);
        plan.setIntervalCount(intervalCount != null ? intervalCount : 1);
        plan.setSubscriptionBillingDayRuleId(subscriptionBillingDayRuleId);
        plan.setEntityTypeId(entityTypeId);
        plan.setCreatedBy(createdBy);

        UUID planId = susBillingDAO.insertSubscriptionPlan(plan);

        // 2) Determine next_billing_date
        String frequencyName = susBillingDAO.getFrequencyNameById(subscriptionFrequencyId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown frequency for id=" + subscriptionFrequencyId));
        Optional<String> billingDayOpt = susBillingDAO.getBillingDayRuleValue(subscriptionBillingDayRuleId);
        LocalDate nextBillingDate = computeNextBillingDate(invoiceDate, frequencyName, plan.getIntervalCount(), billingDayOpt);

        // 3) Insert subscription_instance as ACTIVE
        UUID activeStatusId = susBillingDAO.getSubscriptionInstanceStatusIdByName("ACTIVE");
        SubscriptionInstanceDTO instance = new SubscriptionInstanceDTO();
        instance.setSubscriptionPlanId(planId);
        instance.setStartDate(invoiceDate);
        instance.setNextBillingDate(nextBillingDate);
        instance.setSubscriptionInstanceStatusId(activeStatusId);
        instance.setCreatedBy(createdBy);
        instance.setInvoiceId(invoiceId);

        UUID instanceId = susBillingDAO.insertSubscriptionInstance(instance);

        // 4) Insert first billing history row (SUCCESS now, since invoice is created)
        UUID successStatusId = susBillingDAO.getSubscriptionInstanceStatusIdByName("SUCCESS");
        SubscriptionBillingHistoryDTO history = new SubscriptionBillingHistoryDTO();
        history.setSubscriptionInstanceId(instanceId);
        history.setAmount(amount);
        history.setSubscriptionInstanceStatusId(successStatusId);
        history.setFailureReason(null);
        susBillingDAO.insertSubscriptionBillingHistory(history);

        // 5) Mark instance billed "today" and set computed "next_billing_date"
        susBillingDAO.markInstanceBilled(instanceId, invoiceDate, nextBillingDate, invoiceId);

        // NOTE: Your scheduler should:
        // - Count history rows for this instance
        // - Stop after recurrenceCount billings (12 in your example)
        // That logic is outside this helper to keep it reusable.

        return instanceId;
    }

    /**
     * Compute next billing date from an origin date using frequency and interval.
     * Optionally obey a billing day rule (e.g., "15" for 15th of the month, or "MONDAY" for weekly).
     */
    public LocalDate computeNextBillingDate(LocalDate origin, String frequencyName, int intervalCount, Optional<String> billingDayOpt) {
        String f = frequencyName == null ? "" : frequencyName.trim().toUpperCase();

        switch (f) {
            case "DAILY" -> {
                return origin.plusDays(intervalCount);
            }
            case "WEEKLY" -> {
                // If billingDay is a weekday name, roll forward to that weekday N weeks later
                if (billingDayOpt.isPresent()) {
                    DayOfWeek target = parseDayOfWeek(billingDayOpt.get()).orElse(null);
                    if (target != null) {
                        LocalDate base = origin.plusWeeks(intervalCount);
                        return moveToWeekday(base, target);
                    }
                }
                return origin.plusWeeks(intervalCount);
            }
            case "MONTHLY" -> {
                // If billingDay is a day-of-month number ("1".."28"), align to that DOM
                if (billingDayOpt.isPresent()) {
                    Integer dom = parseDayOfMonth(billingDayOpt.get()).orElse(null);
                    if (dom != null) {
                        LocalDate base = origin.plusMonths(intervalCount);
                        int day = Math.min(dom, base.lengthOfMonth()); // clamp to month length
                        return LocalDate.of(base.getYear(), base.getMonth(), day);
                    }
                }
                return origin.plusMonths(intervalCount);
            }
            case "YEARLY" -> {
                return origin.plusYears(intervalCount);
            }
            default -> throw new IllegalArgumentException("Unsupported frequency: " + frequencyName);
        }
    }

    private Optional<DayOfWeek> parseDayOfWeek(String s) {
        try {
            return Optional.of(DayOfWeek.valueOf(s.trim().toUpperCase()));
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    private Optional<Integer> parseDayOfMonth(String s) {
        try {
            int d = Integer.parseInt(s.trim());
            if (d >= 1 && d <= 28) return Optional.of(d); // safe range; adjust if you support 29/30/31 with clamping
        } catch (Exception ignore) {}
        return Optional.empty();
    }

    private LocalDate moveToWeekday(LocalDate base, DayOfWeek target) {
        int diff = target.getValue() - base.getDayOfWeek().getValue(); // MON=1..SUN=7
        if (diff == 0) return base;
        if (diff < 0) diff += 7;
        return base.plusDays(diff);
    }
}

