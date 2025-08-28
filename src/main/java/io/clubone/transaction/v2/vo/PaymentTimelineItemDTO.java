package io.clubone.transaction.v2.vo;

import java.math.BigDecimal;
import java.time.LocalDate;

public record PaymentTimelineItemDTO(
        LocalDate date,
        BigDecimal amount,
        boolean paid
) {}
