package io.clubone.transaction.vo;

import java.math.BigDecimal;
import java.util.UUID;
import lombok.Data;

@Data
public class BundleComponent {
    private UUID itemId;
    private int quantity;
    private BigDecimal unitPrice;     
    private BigDecimal discountAmount;
    private BigDecimal taxAmount;
    private BigDecimal totalAmount;
    private String description;
}