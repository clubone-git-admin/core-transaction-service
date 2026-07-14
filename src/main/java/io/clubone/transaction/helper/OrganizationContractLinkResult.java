package io.clubone.transaction.helper;

import java.util.UUID;

public record OrganizationContractLinkResult(
        UUID organizationId,
        UUID organizationContractId,
        UUID clientAgreementId,
        boolean created,
        boolean organizationPurchase
) {
}