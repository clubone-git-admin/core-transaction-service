package io.clubone.transaction.vo;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import lombok.Data;

@Data
public class BundleDTO {
 private UUID invoiceEntityId;
 private UUID entityId;
 private String entityDescription;
 private BigDecimal quantity;
 private BigDecimal unitPrice;
 private BigDecimal discountAmount;
 private BigDecimal taxAmount;
 private BigDecimal totalAmount;

 private List<InvoiceItemDTO> items = new ArrayList<>();
}
