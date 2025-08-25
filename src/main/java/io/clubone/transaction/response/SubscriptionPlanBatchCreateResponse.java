package io.clubone.transaction.response;

import java.util.List;

public class SubscriptionPlanBatchCreateResponse {
	private boolean atomic;
	private int totalRequested;
	private int totalSucceeded;
	private int totalFailed;
	private List<PlanCreateResult> results;

	public boolean isAtomic() {
		return atomic;
	}

	public void setAtomic(boolean atomic) {
		this.atomic = atomic;
	}

	public int getTotalRequested() {
		return totalRequested;
	}

	public void setTotalRequested(int totalRequested) {
		this.totalRequested = totalRequested;
	}

	public int getTotalSucceeded() {
		return totalSucceeded;
	}

	public void setTotalSucceeded(int totalSucceeded) {
		this.totalSucceeded = totalSucceeded;
	}

	public int getTotalFailed() {
		return totalFailed;
	}

	public void setTotalFailed(int totalFailed) {
		this.totalFailed = totalFailed;
	}

	public List<PlanCreateResult> getResults() {
		return results;
	}

	public void setResults(List<PlanCreateResult> results) {
		this.results = results;
	}

}
