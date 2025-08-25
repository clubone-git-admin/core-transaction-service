package io.clubone.transaction.response;

import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class FinalizeTransactionResponse {
    private UUID invoiceId;
    private String invoiceStatus;
    private UUID clientPaymentTransactionId;
    private UUID transactionId;
}
