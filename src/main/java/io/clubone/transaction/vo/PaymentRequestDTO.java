package io.clubone.transaction.vo;

import java.math.BigDecimal;
import java.util.UUID;
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
    private UUID paymentGatewayCurrencyTypeId;   // e.g., "CASH"
    private UUID createdBy;
}
