package io.clubone.transaction.dao;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import io.clubone.transaction.response.SubscriptionBillingDayRuleDTO;
import io.clubone.transaction.vo.SubscriptionBillingHistoryDTO;
import io.clubone.transaction.vo.SubscriptionInstanceDTO;
import io.clubone.transaction.vo.SubscriptionPlanDTO;

public interface SubscriptionBillingDAO {

	UUID insertSubscriptionPlan(SubscriptionPlanDTO dto);

	UUID insertSubscriptionInstance(SubscriptionInstanceDTO dto);

	void insertSubscriptionBillingHistory(SubscriptionBillingHistoryDTO dto);

	void markInstanceBilled(UUID subscriptionInstanceId, java.time.LocalDate lastBilledOn,
			java.time.LocalDate nextBillingDate, UUID invoiceId);

	Optional<String> getFrequencyNameById(UUID subscriptionFrequencyId);

	Optional<String> getBillingDayRuleValue(UUID subscriptionBillingDayRuleId); // returns
																				// lu_subscription_billing_day_rule.billing_day

	UUID getSubscriptionInstanceStatusIdByName(String statusName);
	
	List<SubscriptionBillingDayRuleDTO> findAll();
}
