package io.clubone.transaction.v2.vo;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

public record InvoiceAdjustmentDetailDTO(
        UUID billingScheduleAdjustmentId,
        UUID billingScheduleId,
        String adjustmentTypeCode,
        String adjustmentTypeName,
        String signBehavior,
        BigDecimal amount,
        boolean systemGenerated,
        String referenceEntityType,
        UUID referenceEntityId,
        String notes,
        boolean active,
        OffsetDateTime createdOn,
        OffsetDateTime reversedOn,
        String reversalReason
) {
}