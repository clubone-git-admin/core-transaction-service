package io.clubone.transaction.v2.vo;

import java.time.LocalDate;
import java.util.UUID;

public class PlanTermDTO {
	private Integer remainingCycles; // required by table
	private Boolean isActive = true;
	private LocalDate endDate;
	private LocalDate startDate;
	private Integer totalCycles;
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

	public Integer getTotalCycles() {
		return totalCycles;
	}

	public void setTotalCycles(Integer totalCycles) {
		this.totalCycles = totalCycles;
	}

	public LocalDate getStartDate() {
		return startDate;
	}

	public void setStartDate(LocalDate startDate) {
		this.startDate = startDate;
	}

	
}
