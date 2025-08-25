package io.clubone.transaction.vo;

import java.math.BigDecimal;
import java.util.UUID;
import lombok.Data;

@Data
public class InvoiceEntityRow {
 private UUID invoiceId;
 private UUID invoiceEntityId;
 private UUID parentInvoiceEntityId; // can be null
 private String entityDescription;
 private UUID entityId;
 private BigDecimal quantity;
 private BigDecimal unitPrice;
 private BigDecimal discountAmount;
 private BigDecimal taxAmount;
 private BigDecimal totalAmount;
}

