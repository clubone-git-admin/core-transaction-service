package io.clubone.transaction.v2.vo;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import io.clubone.transaction.vo.InvoiceEntityTaxDTO;
import lombok.Data;

@Data
public class Entity {

	private String entityType;
	private UUID entityTypeId;
	private UUID entityId;
	/** Display / description hint (e.g. agreement title from FE). */
	private String entityName;
	private LocalDate startDate;
	private Integer quantity;
	private List<UUID> discountIds;
	private UUID promotionId;
	private List<Bundle> bundles; // Only if type == "agreement"
	private List<Item> items; // For type == "bundle" or "agreement"
	// Optional fields only for type == "item"
	private Double price;
	private UUID pricePlanTemplateId;
	private Boolean upsellItem;
	private UUID clientAgreementId;
	private List<InvoiceEntityTaxDTO> taxes;
	private List<InvoiceEntityDiscountDTO> discounts;

	/** Defaults for leaf lines under this entity (each {@link Item} can override). */
	private UUID billingScheduleId;
	private UUID subscriptionInstanceId;
	private Integer cycleNumber;
	private LocalDate servicePeriodStart;
	private LocalDate servicePeriodEnd;
	private String chargeLineKindCode;
	private UUID chargeLineKindId;
	/**
	 * Required for Agreement and Bundle invoice lines: {@code agreements.agreement_version_id} or
	 * {@code bundles_new.bundle_version_id} (see POS {@code /api/pos/agreement/catalog} {@code versionId}).
	 */
	private UUID entityVersionId;

}
