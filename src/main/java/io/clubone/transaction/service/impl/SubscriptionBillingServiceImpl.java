package io.clubone.transaction.service.impl;

import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import io.clubone.transaction.dao.SubscriptionBillingDAO;
import io.clubone.transaction.response.SubscriptionBillingDayRuleDTO;
import io.clubone.transaction.service.SubscriptionBillingService;

@Service
public class SubscriptionBillingServiceImpl implements SubscriptionBillingService {
	
	@Autowired
	SubscriptionBillingDAO subscriptionBillingDAO;

	@Override
    public List<SubscriptionBillingDayRuleDTO> getAll() {
        return subscriptionBillingDAO.findAll();
    }

}
