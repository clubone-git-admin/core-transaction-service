package io.clubone.transaction.response;

import java.util.UUID;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class CreateInvoiceResponse {
    private UUID invoiceId;
    private String invoiceNumber;
    private String status; // e.g., "PENDING_PAYMENT"
}
