package io.clubone.transaction.dao;

import java.util.UUID;

/**
 * {@code client_payments.client_gateway_mandate} — links gateway mandates to subscription plans.
 */
public interface ClientGatewayMandateDao {

	/**
	 * Sets {@code subscription_plan_id} and {@code client_payment_method_id} on mandate rows whose
	 * {@code parent_invoice_id} matches (checkout parent invoice / finalize payload {@code invoiceId}).
	 *
	 * @return number of rows updated (0 if no matching mandate rows)
	 */
	int updateSubscriptionLinkForParentInvoice(UUID parentInvoiceId, UUID subscriptionPlanId,
			UUID clientPaymentMethodId, UUID modifiedBy);
}
