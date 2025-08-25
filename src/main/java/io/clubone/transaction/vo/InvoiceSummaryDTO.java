package io.clubone.transaction.vo;

import java.math.BigDecimal;
import java.util.UUID;
import lombok.Data;

@Data
public class InvoiceSummaryDTO {
    private UUID clientRoleId;
    private BigDecimal totalAmount;
    private UUID levelId;
}
