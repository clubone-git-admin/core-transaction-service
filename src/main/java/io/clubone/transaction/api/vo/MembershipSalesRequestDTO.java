package io.clubone.transaction.api.vo;

import java.util.List;
import java.util.UUID;
import lombok.Data;

@Data
public class MembershipSalesRequestDTO {
	/*
	 * private UUID memberId; private UUID agreementGroupId; private UUID
	 * agreementClassificationId; private boolean addOnMember; private UUID
	 * agreementId; private UUID locationId; private String barcode; private UUID
	 * leadSourceId; private String effectiveDate; private UUID salesPersonId;
	 * private UUID bundleId; private List<PaymentOptionDTO> paymentOptions; private
	 * List<UpsellItemDTO> upsellItems; private List<PromotionDTO> promotions;
	 * private String agreementComment; private String internalAgreementComment;
	 * private List<FundingSourceDTO> fundingSources; private double totalAmount;
	 * private double balanceAmount; private double balance;
	 */

	private UUID clientRoleId;
	private String memberId;
	private String agreementGroupId;
	private UUID agreementClassificationId;
	private boolean addOnMember;
	private UUID agreementId;
	private UUID locationId;
	private String barcode;
	private String leadSourceId;
	private String effectiveDate; // ISO-8601 string
	private UUID salesPersonId;
	private UUID bundleId;

	private List<PaymentOptionDTO> paymentOptions;
	private List<UpsellItemDTO> upsellItems;
	private List<PromotionDTO> promotions;
	private String agreementComment;
	private String internalAgreementComment;
	private List<FundingSourceDTO> fundingSources;
}
