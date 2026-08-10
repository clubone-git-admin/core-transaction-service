package io.clubone.transaction.request;

import java.util.UUID;

import jakarta.validation.constraints.NotNull;

/**
 * Rebinds {@code subscription_plan.client_payment_method_id} and that plan's gateway mandate only.
 */
public class ReplacePlanPaymentMethodRequest {

	@NotNull
	private UUID clientPaymentMethodId;

	/** Optional checkout invoice that seeded the new card's unlinked mandate. */
	private UUID parentInvoiceId;

	private UUID modifiedBy;

	public UUID getClientPaymentMethodId() {
		return clientPaymentMethodId;
	}

	public void setClientPaymentMethodId(UUID clientPaymentMethodId) {
		this.clientPaymentMethodId = clientPaymentMethodId;
	}

	public UUID getParentInvoiceId() {
		return parentInvoiceId;
	}

	public void setParentInvoiceId(UUID parentInvoiceId) {
		this.parentInvoiceId = parentInvoiceId;
	}

	public UUID getModifiedBy() {
		return modifiedBy;
	}

	public void setModifiedBy(UUID modifiedBy) {
		this.modifiedBy = modifiedBy;
	}
}
