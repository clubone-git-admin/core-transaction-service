package io.clubone.transaction.vo;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.util.List;
import java.util.UUID;
import lombok.Data;

@Data
public class InvoiceDTO {
    private UUID invoiceId;
    private String invoiceNumber;
    private Timestamp invoiceDate;
    private UUID clientRoleId;
    private String billingAddress;
    private UUID invoiceStatusId;
    private String invoiceStatus;
    private BigDecimal totalAmount;
    private BigDecimal subTotal;
    private BigDecimal taxAmount;
    private BigDecimal discountAmount;
    private boolean isPaid;
    private UUID levelId;
    private UUID createdBy;
    private List<InvoiceEntityDTO> lineItems;
}
