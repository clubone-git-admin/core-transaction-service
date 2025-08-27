package io.clubone.transaction.v2.vo;

import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

@Data
public class InvoiceSummaryDTO {
    private UUID invoiceId;
    private String invoiceNumber;
    private LocalDate invoiceDate;

    private BigDecimal amount;       // i.total_amount
    private BigDecimal balanceDue;   // total - paid - writeOff
    private BigDecimal writeOff;     // 0.00 (placeholder)

    private String status;           // lu_invoice_status.status_name

    // Best we can return with current schema; UI can map to a display name
    private UUID createdBy;          
    private String salesRep;         // null unless you wire a user lookup
}

