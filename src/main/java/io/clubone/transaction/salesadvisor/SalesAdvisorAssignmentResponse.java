package io.clubone.transaction.salesadvisor;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import lombok.Data;

@Data
public class SalesAdvisorAssignmentResponse {

	private UUID salesAdvisorUserId;
	private String salesAdvisorName;
	private String salesAdvisorEmail;
	private UUID invoiceId;
	private UUID clientAgreementId;
	private SalesAdvisorRef salesAdvisor;
	private List<SalesAdvisorChangeResponse> history = new ArrayList<>();

	@Data
	public static class SalesAdvisorRef {
		private UUID userId;
		private UUID salesAdvisorUserId;
		private String firstName;
		private String lastName;
		private String fullName;
		private String salesAdvisorName;
		private String email;
		private String salesAdvisorEmail;
	}

	@Data
	public static class SalesAdvisorChangeResponse {
		private UUID salesAdvisorChangeId;
		private UUID previousSalesAdvisorUserId;
		private String previousSalesAdvisorName;
		private UUID newSalesAdvisorUserId;
		private String newSalesAdvisorName;
		private UUID changedByUserId;
		private String changedByName;
		private OffsetDateTime changedOn;
		private UUID invoiceId;
		private String invoiceNumber;
		private UUID clientAgreementId;
		private String clientAgreementCode;
		private String source;
		private String reason;
	}
}
