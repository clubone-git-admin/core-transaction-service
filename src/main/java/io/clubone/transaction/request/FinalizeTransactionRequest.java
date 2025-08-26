package io.clubone.transaction.request;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import lombok.Data;

@Data
public class FinalizeTransactionRequest {
    private UUID invoiceId;
    private UUID clientAgreementId;
    private UUID bundleId;
    private UUID levelId;
    private UUID clientRoleId;

    private BigDecimal totalAmount;
    private BigDecimal subTotal;
    private BigDecimal taxAmount;
    private BigDecimal discountAmount;
    private BigDecimal amountToPayNow;
    private BigDecimal recurringAmount;
    private BigDecimal totalContractAmount;

    private String paymentGatewayCode;
    private String paymentMethodCode;
    private String paymentTypeCode;
    private UUID paymentGatewayCurrencyTypeId;

    private UUID createdBy;
    private List<TransactionLineItemRequest> lineItems;
}

