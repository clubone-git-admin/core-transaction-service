package io.clubone.transaction.vo;

import java.util.UUID;

public record InvoiceSeedRow(
    UUID invoiceId,
    UUID clientRoleId,
    UUID levelId,
    String billingAddress,
    UUID clientAgreementId,
    UUID createdBy
) {}
