package io.clubone.transaction.response;

import java.util.UUID;

import lombok.Data;

@Data
public class CreateInvoiceResponse {
	private UUID invoiceId;
	private String invoiceNumber;
	/** Matches {@code transactions.lu_invoice_status.status_name} (e.g. PENDING). */
	private String status;
	private UUID clientAgreementId;
	private UUID billingRunId;
	private UUID billingCollectionTypeId;
	/** Echo of resolved or requested collection type code, when applicable */
	private String billingCollectionTypeCode;
	private Integer lineItemCount;

	public static CreateInvoiceResponse basic(UUID invoiceId, String invoiceNumber, String status) {
		CreateInvoiceResponse r = new CreateInvoiceResponse();
		r.setInvoiceId(invoiceId);
		r.setInvoiceNumber(invoiceNumber);
		r.setStatus(status);
		return r;
	}
}
