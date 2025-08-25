package io.clubone.transaction.service;

import java.util.List;

import io.clubone.transaction.response.SubscriptionBillingDayRuleDTO;

public interface SubscriptionBillingService {
	
	 List<SubscriptionBillingDayRuleDTO> getAll();

}
