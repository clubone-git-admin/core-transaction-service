package io.clubone.transaction.response;

import java.util.UUID;

public class PlanCreateResult {
	private UUID subscriptionPlanId; // present when success
	private boolean success;
	private String message; // error or success note
	private SubscriptionPlanCreateResponse detail; // counts etc.

	public UUID getSubscriptionPlanId() {
		return subscriptionPlanId;
	}

	public void setSubscriptionPlanId(UUID subscriptionPlanId) {
		this.subscriptionPlanId = subscriptionPlanId;
	}

	public boolean isSuccess() {
		return success;
	}

	public void setSuccess(boolean success) {
		this.success = success;
	}

	public String getMessage() {
		return message;
	}

	public void setMessage(String message) {
		this.message = message;
	}

	public SubscriptionPlanCreateResponse getDetail() {
		return detail;
	}

	public void setDetail(SubscriptionPlanCreateResponse detail) {
		this.detail = detail;
	}

}
