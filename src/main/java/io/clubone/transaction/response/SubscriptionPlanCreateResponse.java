package io.clubone.transaction.response;


import java.util.UUID;

public class SubscriptionPlanCreateResponse {
 private UUID subscriptionPlanId;
 private int cyclePricesInserted;
 private int discountCodesInserted;
 private int entitlementsInserted;
 private int promosInserted;
 private boolean termInserted;
public UUID getSubscriptionPlanId() {
	return subscriptionPlanId;
}
public void setSubscriptionPlanId(UUID subscriptionPlanId) {
	this.subscriptionPlanId = subscriptionPlanId;
}
public int getCyclePricesInserted() {
	return cyclePricesInserted;
}
public void setCyclePricesInserted(int cyclePricesInserted) {
	this.cyclePricesInserted = cyclePricesInserted;
}
public int getDiscountCodesInserted() {
	return discountCodesInserted;
}
public void setDiscountCodesInserted(int discountCodesInserted) {
	this.discountCodesInserted = discountCodesInserted;
}
public int getEntitlementsInserted() {
	return entitlementsInserted;
}
public void setEntitlementsInserted(int entitlementsInserted) {
	this.entitlementsInserted = entitlementsInserted;
}
public int getPromosInserted() {
	return promosInserted;
}
public void setPromosInserted(int promosInserted) {
	this.promosInserted = promosInserted;
}
public boolean isTermInserted() {
	return termInserted;
}
public void setTermInserted(boolean termInserted) {
	this.termInserted = termInserted;
}


}
