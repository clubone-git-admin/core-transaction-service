package io.clubone.transaction.v2.vo;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonAlias;

import io.clubone.transaction.vo.InvoiceEntityTaxDTO;
import lombok.Data;

@Data
public class Item {

	private UUID entityId;
    private Integer quantity;
    private Double price;
    @JsonAlias("planTemplateId")
    private UUID pricePlanTemplateId;
    private Boolean upsellItem;
	/** Line-level contract / service start; falls back to parent entity start when null. */
	private LocalDate startDate;
	/** Display name from FE (stored on invoice line description when present). */
	private String entityName;
	/** Merged with parent {@link Entity#getDiscountIds()} when resolving discounts. */
	private List<UUID> discountIds;
    private List<InvoiceEntityTaxDTO> taxes;
	/**
	 * When set, tax is taken from the client instead of finance tax group resolution.
	 * {@link #taxPct} is optional metadata for a single-rate display row.
	 */
	private BigDecimal taxAmount;
	private BigDecimal taxPct;
	/**
	 * When set together with {@link #taxRateAllocationId} and {@link #taxAmount}, a row is written to
	 * {@code transactions.invoice_entity_tax} (finance-backed audit). Load from your tax/catalog API.
	 */
	private UUID taxRateId;
	private UUID taxRateAllocationId;
    private List<InvoiceEntityPriceBandDTO> priceBands;

	private UUID billingScheduleId;
	private UUID subscriptionInstanceId;
	private Integer cycleNumber;
	private LocalDate servicePeriodStart;
	private LocalDate servicePeriodEnd;
	/** e.g. ANCILLARY, SUBSCRIPTION_RECURRING — resolved against lu_charge_line_kind.code */
	private String chargeLineKindCode;
	private UUID chargeLineKindId;
	/**
	 * Required on invoice item lines: {@code items.item_version_id} (use POS catalog {@code versionId} per item).
	 */
	private UUID entityVersionId;
}
