package io.clubone.transaction.request;

import java.time.LocalDate;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonInclude;

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
}
