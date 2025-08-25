package io.clubone.transaction.request;

import jakarta.validation.constraints.NotEmpty;
import java.util.List;
import java.util.UUID;

public class SubscriptionPlanBatchCreateRequest {
	@NotEmpty
	private List<SubscriptionPlanCreateRequest> plans;
	/** If true (default), all-or-nothing. If false, per-plan transactions. */
	private Boolean atomic = true;
	private UUID createdBy;

	public List<SubscriptionPlanCreateRequest> getPlans() {
		return plans;
	}

	public void setPlans(List<SubscriptionPlanCreateRequest> plans) {
		this.plans = plans;
	}

	public Boolean getAtomic() {
		return atomic;
	}

	public void setAtomic(Boolean atomic) {
		this.atomic = atomic;
	}

	public UUID getCreatedBy() {
		return createdBy;
	}

	public void setCreatedBy(UUID createdBy) {
		this.createdBy = createdBy;
	}
	

}
