package io.clubone.transaction.salesadvisor;

import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonAlias;

import lombok.Data;

@Data
public class SalesAdvisorUpdateRequest {

	@JsonAlias({ "salesAdvisorId", "sales_advisor_user_id", "sales_advisor_id" })
	private UUID salesAdvisorUserId;

	@JsonAlias({ "changedBy", "changed_by_user_id" })
	private UUID changedByUserId;

	private String source;
	private String reason;

	@JsonAlias({ "invoice_id" })
	private UUID invoiceId;

	@JsonAlias({ "client_agreement_id" })
	private UUID clientAgreementId;
}
