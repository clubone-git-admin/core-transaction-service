package io.clubone.transaction.vo;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;
import lombok.Data;

@Data
public class InvoiceFlatRow {
 private UUID invoiceId;
 private String invoiceNumber;
 private LocalDate invoiceDate;
 private BigDecimal totalAmount;
 private BigDecimal subTotal;
 private BigDecimal taxAmount;
 private BigDecimal discountAmount;
 private String transactionCode;
 private UUID clientAgreementId;
 private UUID clientPaymentTransactionId;
 private OffsetDateTime transactionDate;
}
