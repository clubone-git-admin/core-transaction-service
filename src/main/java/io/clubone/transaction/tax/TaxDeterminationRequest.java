package io.clubone.transaction.tax;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

public final class TaxDeterminationRequest {
	private final UUID applicationId;
	private final UUID itemId;
	private final UUID levelId;
	private final UUID clientRoleId;
	private final BigDecimal unitPrice;
	private final int quantity;
	private final BigDecimal discountAmount;
	private final LocalDate asOfDate;
	private final UUID actorId;
	private final String sourceType;
	private final UUID sourceId;

	public TaxDeterminationRequest(
			UUID applicationId,
			UUID itemId,
			UUID levelId,
			UUID clientRoleId,
			BigDecimal unitPrice,
			int quantity,
			BigDecimal discountAmount,
			LocalDate asOfDate,
			UUID actorId,
			String sourceType,
			UUID sourceId
	) {
		this.applicationId = applicationId;
		this.itemId = itemId;
		this.levelId = levelId;
		this.clientRoleId = clientRoleId;
		this.unitPrice = unitPrice;
		this.quantity = quantity;
		this.discountAmount = discountAmount;
		this.asOfDate = asOfDate == null ? LocalDate.now() : asOfDate;
		this.actorId = actorId;
		this.sourceType = sourceType;
		this.sourceId = sourceId;
	}

	public UUID getApplicationId() { return applicationId; }
	public UUID getItemId() { return itemId; }
	public UUID getLevelId() { return levelId; }
	public UUID getClientRoleId() { return clientRoleId; }
	public BigDecimal getUnitPrice() { return unitPrice; }
	public int getQuantity() { return quantity; }
	public BigDecimal getDiscountAmount() { return discountAmount; }
	public LocalDate getAsOfDate() { return asOfDate; }
	public UUID getActorId() { return actorId; }
	public String getSourceType() { return sourceType; }
	public UUID getSourceId() { return sourceId; }
}
