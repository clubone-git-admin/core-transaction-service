package io.clubone.transaction.controller;

import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import io.clubone.transaction.response.SubscriptionBillingDayRuleDTO;
import io.clubone.transaction.service.SubscriptionBillingService;

@RestController
@RequestMapping("/subscription-billing-day-rules")
public class SubscriptionBillingController {
	
	@Autowired
	private SubscriptionBillingService subscriptionBillingService;

	@GetMapping
	public List<SubscriptionBillingDayRuleDTO> getAll() {
		return subscriptionBillingService.getAll();
	}

}
