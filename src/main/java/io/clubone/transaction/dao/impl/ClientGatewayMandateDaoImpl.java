package io.clubone.transaction.dao.impl;

import java.util.UUID;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import io.clubone.transaction.dao.ClientGatewayMandateDao;

@Repository
public class ClientGatewayMandateDaoImpl implements ClientGatewayMandateDao {

	@Autowired
	@Qualifier("cluboneJdbcTemplate")
	private JdbcTemplate cluboneJdbcTemplate;

	@Override
	public int updateSubscriptionLinkForParentInvoice(UUID parentInvoiceId, UUID subscriptionPlanId,
			UUID clientPaymentMethodId, UUID modifiedBy) {
		if (parentInvoiceId == null || subscriptionPlanId == null || clientPaymentMethodId == null) {
			return 0;
		}
		final String sql = """
				UPDATE client_payments.client_gateway_mandate
				SET subscription_plan_id = ?,
				    client_payment_method_id = ?,
				    modified_on = NOW(),
				    modified_by = ?
				WHERE parent_invoice_id = ?
				""";
		return cluboneJdbcTemplate.update(sql, subscriptionPlanId, clientPaymentMethodId,
				modifiedBy, parentInvoiceId);
	}
}
