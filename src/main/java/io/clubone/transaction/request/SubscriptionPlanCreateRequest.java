package io.clubone.transaction.request;

import jakarta.validation.constraints.*;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import io.clubone.transaction.v2.vo.CyclePriceDTO;
import io.clubone.transaction.v2.vo.DiscountCodeDTO;
import io.clubone.transaction.v2.vo.EntitlementDTO;
import io.clubone.transaction.v2.vo.PlanTermDTO;
import io.clubone.transaction.v2.vo.PromoDTO;
import io.clubone.transaction.v2.vo.SubscriptionBillingPromotionDTO;
import io.clubone.transaction.v2.vo.SubscriptionPlanPromoDTO;

public class SubscriptionPlanCreateRequest {
	private UUID createdBy;
	@NotNull
	private UUID entityId;
	@NotNull
	private UUID clientPaymentMethodId;
	@NotNull
	private UUID subscriptionFrequencyId;
	private Integer intervalCount = 1;
	@NotNull
	private UUID subscriptionBillingDayRuleId;
	@NotNull
	private UUID entityTypeId;
	private UUID clientAgreementId;
	private UUID agreementTermId;

	@NotNull
	private LocalDate contractStartDate;
	@NotNull
	private LocalDate contractEndDate;
	
	private UUID invoiceId;
	private UUID levelId;

	// Children (all optional lists)
	private List<CyclePriceDTO> cyclePrices;
	private List<DiscountCodeDTO> discountCodes;
	private List<EntitlementDTO> entitlements;
	private List<PromoDTO> promos;
	private PlanTermDTO term;
	private Integer remainingCycles;
	private Integer currentCycle;
	private Integer totalCycles;
	 private List<SubscriptionPlanPromoDTO> subscriptionPlanPromos;
	    //private SubscriptionBillingHistoryDTO subscriptionBillingHistory;
	    private List<SubscriptionBillingPromotionDTO> subscriptionBillingPromotions;

	public UUID getEntityId() {
		return entityId;
	}

	public void setEntityId(UUID entityId) {
		this.entityId = entityId;
	}

	public UUID getClientPaymentMethodId() {
		return clientPaymentMethodId;
	}

	public void setClientPaymentMethodId(UUID clientPaymentMethodId) {
		this.clientPaymentMethodId = clientPaymentMethodId;
	}

	public UUID getSubscriptionFrequencyId() {
		return subscriptionFrequencyId;
	}

	public void setSubscriptionFrequencyId(UUID subscriptionFrequencyId) {
		this.subscriptionFrequencyId = subscriptionFrequencyId;
	}

	public Integer getIntervalCount() {
		return intervalCount;
	}

	public void setIntervalCount(Integer intervalCount) {
		this.intervalCount = intervalCount;
	}

	public UUID getSubscriptionBillingDayRuleId() {
		return subscriptionBillingDayRuleId;
	}

	public void setSubscriptionBillingDayRuleId(UUID subscriptionBillingDayRuleId) {
		this.subscriptionBillingDayRuleId = subscriptionBillingDayRuleId;
	}

	public UUID getEntityTypeId() {
		return entityTypeId;
	}

	public void setEntityTypeId(UUID entityTypeId) {
		this.entityTypeId = entityTypeId;
	}

	public LocalDate getContractStartDate() {
		return contractStartDate;
	}

	public void setContractStartDate(LocalDate contractStartDate) {
		this.contractStartDate = contractStartDate;
	}

	public LocalDate getContractEndDate() {
		return contractEndDate;
	}

	public void setContractEndDate(LocalDate contractEndDate) {
		this.contractEndDate = contractEndDate;
	}

	public List<CyclePriceDTO> getCyclePrices() {
		return cyclePrices;
	}

	public void setCyclePrices(List<CyclePriceDTO> cyclePrices) {
		this.cyclePrices = cyclePrices;
	}

	public List<DiscountCodeDTO> getDiscountCodes() {
		return discountCodes;
	}

	public void setDiscountCodes(List<DiscountCodeDTO> discountCodes) {
		this.discountCodes = discountCodes;
	}

	public List<EntitlementDTO> getEntitlements() {
		return entitlements;
	}

	public void setEntitlements(List<EntitlementDTO> entitlements) {
		this.entitlements = entitlements;
	}

	public List<PromoDTO> getPromos() {
		return promos;
	}

	public void setPromos(List<PromoDTO> promos) {
		this.promos = promos;
	}

	public PlanTermDTO getTerm() {
		return term;
	}

	public void setTerm(PlanTermDTO term) {
		this.term = term;
	}

	public UUID getCreatedBy() {
		return createdBy;
	}

	public void setCreatedBy(UUID createdBy) {
		this.createdBy = createdBy;
	}

	public UUID getInvoiceId() {
		return invoiceId;
	}

	public void setInvoiceId(UUID invoiceId) {
		this.invoiceId = invoiceId;
	}

	public UUID getLevelId() {
		return levelId;
	}

	public void setLevelId(UUID levelId) {
		this.levelId = levelId;
	}

	public UUID getClientAgreementId() {
		return clientAgreementId;
	}

	public void setClientAgreementId(UUID clientAgreementId) {
		this.clientAgreementId = clientAgreementId;
	}

	public UUID getAgreementTermId() {
		return agreementTermId;
	}

	public void setAgreementTermId(UUID agreementTermId) {
		this.agreementTermId = agreementTermId;
	}

	public Integer getRemainingCycles() {
		return remainingCycles;
	}

	public void setRemainingCycles(Integer remainingCycles) {
		this.remainingCycles = remainingCycles;
	}

	public Integer getCurrentCycle() {
		return currentCycle;
	}

	public void setCurrentCycle(Integer currentCycle) {
		this.currentCycle = currentCycle;
	}

	public Integer getTotalCycles() {
		return totalCycles;
	}

	public void setTotalCycles(Integer totalCycles) {
		this.totalCycles = totalCycles;
	}

	public List<SubscriptionPlanPromoDTO> getSubscriptionPlanPromos() {
		return subscriptionPlanPromos;
	}

	public void setSubscriptionPlanPromos(List<SubscriptionPlanPromoDTO> subscriptionPlanPromos) {
		this.subscriptionPlanPromos = subscriptionPlanPromos;
	}

	public List<SubscriptionBillingPromotionDTO> getSubscriptionBillingPromotions() {
		return subscriptionBillingPromotions;
	}

	public void setSubscriptionBillingPromotions(List<SubscriptionBillingPromotionDTO> subscriptionBillingPromotions) {
		this.subscriptionBillingPromotions = subscriptionBillingPromotions;
	}
	
	

}
