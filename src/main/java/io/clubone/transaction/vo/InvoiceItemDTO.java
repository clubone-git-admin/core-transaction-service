package io.clubone.transaction.vo;

import java.math.BigDecimal;
import java.util.UUID;

import lombok.Data;

@Data
public class InvoiceItemDTO {
 private UUID invoiceEntityId;
 private UUID parentInvoiceEntityId;
 private UUID entityId;
 private String entityDescription;
 private BigDecimal quantity;
 private BigDecimal unitPrice;
 private BigDecimal discountAmount;
 private BigDecimal taxAmount;
 private BigDecimal totalAmount;
}
