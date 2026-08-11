package io.clubone.transaction.dao;

import java.util.List;
import java.util.UUID;

/**
 * {@code client_payments.client_gateway_mandate} — 1:1 active mandate per subscription plan.
 * Same card / gateway token may be shared; each plan owns its own mandate row.
 */
public interface ClientGatewayMandateDao {

	/**
	 * Ensures every non-fee subscription plan from finalize has its own active mandate row,
	 * cloned from the checkout seed mandate ({@code parent_invoice_id}, unlinked or first link).
	 * Falls back to an unlinked seed on the same {@code client_payment_method_id} when invoice seed is missing.
	 *
	 * @return number of mandate rows inserted or updated
	 */
	int ensureOneMandatePerSubscriptionPlan(UUID parentInvoiceId, List<UUID> subscriptionPlanIds,
			UUID clientPaymentMethodId, UUID modifiedBy);

	/**
	 * Soft-deactivates every active mandate linked to the plan (cancel / card-replace).
	 *
	 * @return number of rows deactivated
	 */
	int deactivateActiveMandatesForPlan(UUID subscriptionPlanId, UUID modifiedBy);

	/**
	 * Deactivates the plan's current mandate(s), then links/clones a seed mandate for
	 * {@code newClientPaymentMethodId} onto this plan only (other plans untouched).
	 *
	 * @param parentInvoiceId optional checkout invoice that seeded the new card mandate
	 * @return number of mandate rows inserted or updated for the new binding
	 */
	int rebindMandateForPlan(UUID subscriptionPlanId, UUID newClientPaymentMethodId, UUID parentInvoiceId,
			UUID modifiedBy);
}
