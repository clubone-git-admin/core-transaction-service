package io.clubone.transaction.vo;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import lombok.Data;

@Data
public class TransactionEntityDTO {
    private UUID entityTypeId;
    private UUID entityId;
    private String entityDescription;
    private int quantity;
    private BigDecimal unitPrice;
    private BigDecimal discountAmount;
    private BigDecimal taxAmount;
    private BigDecimal totalAmount;
    private UUID parentTransactionEntityId;
    private List<TransactionEntityTaxDTO> taxes;
}

