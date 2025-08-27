package io.clubone.transaction.v2.vo;

import java.time.LocalDate;
import java.util.UUID;

public class PlanTermDTO {
	private Integer remainingCycles; // required by table
	private Boolean isActive = true;
	private LocalDate endDate;
	//private UUID subscriptionPlanId;// required

	public Integer getRemainingCycles() {
		return remainingCycles;
	}

	public void setRemainingCycles(Integer remainingCycles) {
		this.remainingCycles = remainingCycles;
	}

	public Boolean getIsActive() {
		return isActive;
	}

	public void setIsActive(Boolean isActive) {
		this.isActive = isActive;
	}

	public LocalDate getEndDate() {
		return endDate;
	}

	public void setEndDate(LocalDate endDate) {
		this.endDate = endDate;
	}

}
