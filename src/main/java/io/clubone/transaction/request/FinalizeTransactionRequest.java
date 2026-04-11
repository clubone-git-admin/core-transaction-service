package io.clubone.transaction.request;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import lombok.Data;

@Data
public class FinalizeTransactionRequest {
    private UUID invoiceId;
    private String billingAddress;
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
    
    /** Used for gateway flows (e.g. Razorpay verify); must match the payment already captured. */
    private UUID clientPaymentTransactionId;

    /**
     * Optional receipt/email display fields from the gateway or POS (avoid hardcoding in finalize).
     * When null, notifications use generic values derived from payment method codes.
     */
    private String paymentInstrumentType;
    private String paymentInstrumentBrand;
    private String paymentInstrumentLast4;
    private String paymentAuthorizationReference;

    /**
     * When non-empty, each entry is POSTed to the billing quote line-items API to load plan/pricing details
     * (replaces DB-driven subscription build in {@link io.clubone.transaction.helper.SubscriptionPlanHelper}).
     */
    private List<BillingQuoteFinalizeSpec> billingQuoteFinalizeSpecs;
}

