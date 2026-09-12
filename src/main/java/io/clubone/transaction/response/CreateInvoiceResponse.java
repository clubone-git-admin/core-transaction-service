package io.clubone.transaction.response;

import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonInclude;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class CreateInvoiceResponse {

	private UUID invoiceId;
	private String invoiceNumber;
	private String status;
	private UUID clientAgreementId;
	private Map<UUID, UUID> clientAgreementIdsByAgreementId;
	private UUID billingRunId;
	private UUID billingCollectionTypeId;
	private String billingCollectionTypeCode;
	private Integer lineItemCount;

	public static CreateInvoiceResponse basic(UUID invoiceId, String invoiceNumber, String status) {
		CreateInvoiceResponse response = new CreateInvoiceResponse();
		response.setInvoiceId(invoiceId);
		response.setInvoiceNumber(invoiceNumber);
		response.setStatus(status);
		return response;
	}
}