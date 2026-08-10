package io.clubone.transaction.vo;

import java.math.BigDecimal;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonProperty;

import lombok.Data;

@Data
public class PaymentRequestDTO {
    private UUID clientRoleId;
    /** When set, payment service can allocate cash/card capture to this receivable. */
    private UUID invoiceId;
    private BigDecimal amount;
    private String paymentGatewayCode; // e.g., "MANUAL"
    private String paymentMethodCode;  // e.g., "CASH"
    private String paymentTypeCode;
    /**
     * Gateway supported-currency id. Serialized as {@code currencyId} for payment-service.
     * Also accepts FE/legacy name {@code paymentGatewayCurrencyTypeId}.
     */
    @JsonProperty("currencyId")
    @JsonAlias("paymentGatewayCurrencyTypeId")
    private UUID paymentGatewayCurrencyTypeId;
    /** ISO-4217; payment-service can resolve supported-currency id from this + gateway. */
    private String currencyCode;
    private UUID createdBy;
}
