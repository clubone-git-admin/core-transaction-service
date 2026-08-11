package io.clubone.transaction.response;

import java.util.List;
import java.util.UUID;
import lombok.Data;

@Data
public class FinalizeTransactionResponse {
    private UUID invoiceId;
    private String status;
    private UUID clientPaymentTransactionId;
    private UUID transactionId;
    private String message;
    private List<GiftcardIssuedDTO> issuedGiftcards = List.of();

    public FinalizeTransactionResponse(
            UUID invoiceId,
            String status,
            UUID clientPaymentTransactionId,
            UUID transactionId,
            String message
    ) {
        this.invoiceId = invoiceId;
        this.status = status;
        this.clientPaymentTransactionId = clientPaymentTransactionId;
        this.transactionId = transactionId;
        this.message = message;
    }
}