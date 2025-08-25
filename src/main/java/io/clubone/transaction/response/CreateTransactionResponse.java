package io.clubone.transaction.response;

import lombok.AllArgsConstructor;
import lombok.Data;
import java.util.UUID;

@Data
@AllArgsConstructor
public class CreateTransactionResponse {
    private UUID transactionId;
}

