package io.clubone.transaction.response;

import java.time.LocalDate;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;

import lombok.Data;

/**
 * Response from {@code POST /vendors/billing/api/quote/line-items}.
 * Nested structures are kept as {@link JsonNode} so we stay compatible as the billing API evolves.
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class BillingQuoteLineItemsResponse {

	private String entityTypeCode;
	private UUID entityId;
	private UUID planTemplateId;
	private LocalDate startDate;
	private String timezone;

	private JsonNode billing;
	private JsonNode planPosDetail;
	private JsonNode appliedPricing;
	private JsonNode lineItems;
	private JsonNode scheduleLineItems;
	private JsonNode recurring;
}
