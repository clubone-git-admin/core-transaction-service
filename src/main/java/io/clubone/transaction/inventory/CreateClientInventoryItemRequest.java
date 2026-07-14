package io.clubone.transaction.inventory;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record CreateClientInventoryItemRequest(
        UUID clientInventoryAccountId,
        UUID clientRoleId,
        UUID applicationId,

        UUID itemId,
        UUID itemVersionId,
        String itemCodeSnapshot,
        String itemNameSnapshot,
        String itemDescriptionSnapshot,

        UUID inventorySourceTypeId,
        UUID originalInventoryValueTypeId,
        UUID currentInventoryValueTypeId,
        UUID inventoryItemStatusId,
        UUID inventoryUnitTypeId,

        UUID moduleId,
        UUID serviceKindLookupId,
        UUID serviceCategoryId,
        UUID serviceSubcategoryId,
        UUID serviceTypeId,
        UUID sessionTypeLookupId,
        UUID serviceMappingId,
        UUID entitlementTierId,

        Integer durationMinutes,
        BigDecimal originalQuantity,

        UUID owningLevelId,
        UUID usableLevelId,

        UUID redemptionRuleId,

        String notes,
        Map<String, Object> metadata,
        String idempotencyKey,
        UUID createdBy
) {
}
