package io.clubone.transaction.inventory;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

final class InventoryProvisioningModels {

    private InventoryProvisioningModels() {
    }

    record InvoiceHeader(
            UUID invoiceId,
            String invoiceNumber,
            UUID clientRoleId,
            UUID levelId
    ) {
    }

    record InvoiceEntityLine(
            UUID invoiceEntityId,
            UUID parentInvoiceEntityId,
            String entityType,
            UUID entityId,
            UUID entityVersionId,
            String description,
            BigDecimal quantity
    ) {
    }

    record ItemEntitlement(
            UUID invoiceEntityId,
            String sourceEntityType,
            UUID sourceEntityId,
            UUID sourceEntityVersionId,

            UUID packageId,
            UUID packageVersionId,
            UUID packageItemId,

            UUID itemId,
            UUID itemVersionId,
            UUID applicationId,
            String itemCode,
            String itemName,
            String itemDescription,
            BigDecimal quantity
    ) {
    }

    record MappingContext(
            UUID serviceMappingId,
            UUID applicationId,
            UUID moduleId,
            UUID serviceKindLookupId,
            UUID serviceCategoryId,
            UUID serviceSubcategoryId,
            UUID serviceTypeId,
            Integer defaultDurationMinutes,
            UUID entitlementTierId,
            UUID redemptionRuleId,
            UUID reinstatementRuleId,
            int priority
    ) {
    }
}
