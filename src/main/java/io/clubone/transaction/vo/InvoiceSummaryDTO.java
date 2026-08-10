package io.clubone.transaction.vo;

import java.math.BigDecimal;
import java.util.UUID;
import lombok.Data;

@Data
public class InvoiceSummaryDTO {
    private UUID clientRoleId;
    private BigDecimal totalAmount;
    private UUID levelId;
    /** From transactions.invoice — used when finalize request omits clientAgreementId. */
    private UUID clientAgreementId;
    /** Transactional currency stamped on the invoice (ISO-4217). */
    private String currencyCode;
}
