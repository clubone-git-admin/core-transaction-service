package io.clubone.transaction.request;

import java.time.LocalDate;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import lombok.Data;

/**
 * One line to finalize billing quote; sent as the body to the billing service
 * {@code POST /vendors/billing/api/quote/line-items}.
 */
@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class BillingQuoteFinalizeSpec {

	private String entityTypeCode;
	private UUID entityId;
	private UUID planTemplateId;
	private LocalDate startDate;
	private String timezone;
	private Integer quantity;
	private LocalDate chargeDate;
	private LocalDate chargeEndDate;
	private UUID levelId;
	/** Promotion applied at checkout; must be re-quoted so schedules match POS amounts. */
	private UUID promotionId;
	/** Quote mode passed through to vendor (e.g. INITIAL / SCHEDULE). */
	private String quoteMode;

	/**
	 * Physical locker selected in POS for this agreement purchase.
	 * WRITE_ONLY keeps it available to transaction finalization but prevents it
	 * from being forwarded to the vendor billing quote endpoint.
	 */
	@JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
	private UUID locationLockerId;
}
