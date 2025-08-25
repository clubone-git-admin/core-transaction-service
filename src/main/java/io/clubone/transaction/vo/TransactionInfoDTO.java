package io.clubone.transaction.vo;


import java.time.OffsetDateTime;
import java.util.UUID;

import lombok.Data;

@Data
public class TransactionInfoDTO {
 private String transactionCode;
 private UUID clientAgreementId;
 private UUID clientPaymentTransactionId;
 private OffsetDateTime transactionDate;
}
