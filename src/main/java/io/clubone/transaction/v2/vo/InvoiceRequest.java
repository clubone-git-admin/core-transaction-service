package io.clubone.transaction.v2.vo;

import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonAlias;

import io.swagger.annotations.ApiModelProperty;
import lombok.Data;

@Data
public class InvoiceRequest {

	private static final UUID DEFAULT_APPLICATION_ID =
			UUID.fromString("5949a200-82fb-4171-9001-0f77ac439011");

	private UUID clientRoleId;
	/**
	 * Either {@code locations.levels.level_id} (PK) or {@code locations.levels.reference_entity_id}
	 * (e.g. location id); stored on the invoice as {@code level_id} after resolution.
	 */
	@ApiModelProperty(value = "levels.level_id or levels.reference_entity_id")
	@JsonAlias("locationId")
	private UUID levelId;
	private String billingAddress;
	private boolean isPaid;
	private UUID createdBy;
	private List<Entity> entities;

	/** Optional: subscription billing batch that produced this invoice */
	private UUID billingRunId;
	/** FK to transactions.lu_billing_collection_type */
	private UUID billingCollectionTypeId;
	/**
	 * Alternative to billingCollectionTypeId — e.g. STANDARD, PAID_IN_FULL (case-insensitive
	 * match on lu_billing_collection_type.code)
	 */
	private String billingCollectionTypeCode;

	/** Application context for entitlements, fee lookup, promotions (defaults if omitted). */
	private UUID applicationId;

	/** Optional POS / checkout metadata from the client (accepted for forward compatibility). */
	private String currencyCode;
	private String timezone;
	private String availabilityTypeCode;

	public UUID resolvedApplicationId() {
		return applicationId != null ? applicationId : DEFAULT_APPLICATION_ID;
	}

}
