package io.clubone.transaction.vo;

import java.sql.Timestamp;
import java.util.List;
import java.util.UUID;
import lombok.Data;

@Data
public class TransactionDTO {
    private UUID transactionId;
    private UUID clientAgreementId;
    private UUID clientPaymentTransactionId;
    private UUID levelId;
    private UUID invoiceId;
    private Timestamp transactionDate;
    private List<TransactionEntityDTO> lineItems;
    private UUID createdBy;
}
