package io.clubone.transaction.response;

import java.time.OffsetDateTime;
import java.util.UUID;

public record GiftcardIssuedDTO(
        UUID clientGiftcardId,
        String cardNumber,
        String cardLast4,
        long balanceMinor,
        String currencyCode,
        String statusCode,
        OffsetDateTime expiresOn
) {}