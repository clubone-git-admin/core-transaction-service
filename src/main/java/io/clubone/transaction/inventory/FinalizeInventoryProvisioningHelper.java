package io.clubone.transaction.inventory;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import static io.clubone.transaction.inventory.InventoryProvisioningModels.*;

@Slf4j
@Component
@RequiredArgsConstructor
public class FinalizeInventoryProvisioningHelper {

    private final InvoiceInventoryProvisioningRepository repository;
    private final ClientInventoryApiClient apiClient;
    private final InventoryProvisioningProperties properties;
    private final ObjectMapper objectMapper;
    
    private static final UUID INVENTORY_CREATED_BY =
            UUID.fromString(
                    "1934776b-1912-4886-9890-023f21f6ba3b"
            );

    /**
     * Call this only after payment/invoice finalization has succeeded.
     *
     * Do not invoke it before the local finalize transaction commits.
     * When it is wired into the finalize implementation, prefer an
     * AFTER_COMMIT event or a durable outbox worker.
     */
    public InventoryProvisioningResult provisionForFinalizedInvoice(
            UUID invoiceId,
            UUID paymentTransactionId,
            UUID actorId,
            UUID locationId,
            UUID applicationId,
            String correlationId) {

        Objects.requireNonNull(
                invoiceId,
                "invoiceId is required"
        );
        Objects.requireNonNull(
                actorId,
                "actorId is required"
        );
        Objects.requireNonNull(
                locationId,
                "locationId is required"
        );
        Objects.requireNonNull(
                applicationId,
                "applicationId is required"
        );

        InvoiceHeader invoice =
                repository.loadInvoice(invoiceId);

        if (invoice.clientRoleId() == null) {
            throw new InventoryProvisioningException(
                    "Invoice does not contain clientRoleId: "
                            + invoiceId
            );
        }

        if (invoice.levelId() == null) {
            throw new InventoryProvisioningException(
                    "Invoice does not contain levelId: "
                            + invoiceId
            );
        }

        List<InvoiceEntityLine> invoiceEntities =
                repository.loadRootInvoiceEntities(invoiceId);

        List<ItemEntitlement> entitlements =
                expandEntitlements(invoiceEntities);

        if (entitlements.isEmpty()) {
            entitlements = repository
                    .loadAgreementSnapshotEntitlements(invoiceId);
            log.info(
                    "[inventory-provisioning] step=agreement_snapshot_entitlements "
                            + "invoiceId={} outcome={} entitlementCount={}",
                    invoiceId,
                    entitlements.isEmpty() ? "empty" : "resolved",
                    entitlements.size()
            );
        }

        log.info(
                "[inventory-provisioning] step=entitlements_resolved "
                        + "invoiceId={} clientRoleId={} levelId={} "
                        + "invoiceEntityCount={} entitlementCount={}",
                invoiceId,
                invoice.clientRoleId(),
                invoice.levelId(),
                invoiceEntities.size(),
                entitlements.size()
        );

        List<ProvisionedInventoryItem> created =
                new ArrayList<>();
        int skipped = 0;

        for (ItemEntitlement entitlement : entitlements) {
            log.info(
                    "[inventory-provisioning] step=entitlement_resolved "
                            + "invoiceId={} invoiceEntityId={} "
                            + "sourceEntityType={} sourceEntityId={} "
                            + "sourceEntityVersionId={} packageId={} "
                            + "packageVersionId={} packageItemId={} "
                            + "itemId={} itemVersionId={} itemCode={} "
                            + "quantity={} applicationId={}",
                    invoiceId,
                    entitlement.invoiceEntityId(),
                    entitlement.sourceEntityType(),
                    entitlement.sourceEntityId(),
                    entitlement.sourceEntityVersionId(),
                    entitlement.packageId(),
                    entitlement.packageVersionId(),
                    entitlement.packageItemId(),
                    entitlement.itemId(),
                    entitlement.itemVersionId(),
                    entitlement.itemCode(),
                    entitlement.quantity(),
                    entitlement.applicationId()
            );

            MappingContext mapping =
                    repository.resolveMapping(
                            entitlement.itemVersionId()
                    );

            if (mapping == null) {
                log.warn(
                        "[inventory-provisioning] step=mapping_resolution "
                                + "outcome=missing action=inventory_api_called_without_mapping "
                                + "invoiceId={} invoiceEntityId={} "
                                + "packageId={} packageVersionId={} "
                                + "packageItemId={} itemId={} "
                                + "itemVersionId={} quantity={} "
                                + "mappingDependentFields=null",
                        invoiceId,
                        entitlement.invoiceEntityId(),
                        entitlement.packageId(),
                        entitlement.packageVersionId(),
                        entitlement.packageItemId(),
                        entitlement.itemId(),
                        entitlement.itemVersionId(),
                        entitlement.quantity()
                );
            } else {
                log.info(
                        "[inventory-provisioning] step=mapping_resolution "
                                + "outcome=resolved invoiceId={} "
                                + "invoiceEntityId={} itemVersionId={} "
                                + "serviceMappingId={} redemptionRuleId={} "
                                + "moduleId={} serviceKindLookupId={} "
                                + "serviceCategoryId={} serviceSubcategoryId={} "
                                + "serviceTypeId={} entitlementTierId={} "
                                + "defaultDurationMinutes={}",
                        invoiceId,
                        entitlement.invoiceEntityId(),
                        entitlement.itemVersionId(),
                        mapping.serviceMappingId(),
                        mapping.redemptionRuleId(),
                        mapping.moduleId(),
                        mapping.serviceKindLookupId(),
                        mapping.serviceCategoryId(),
                        mapping.serviceSubcategoryId(),
                        mapping.serviceTypeId(),
                        mapping.entitlementTierId(),
                        mapping.defaultDurationMinutes()
                );

                if (properties.requireRedemptionRule()
                        && mapping.redemptionRuleId() == null) {
                    throw new InventoryProvisioningException(
                            "No active redemption rule was found for "
                                    + "serviceMappingId="
                                    + mapping.serviceMappingId()
                                    + ", itemVersionId="
                                    + entitlement.itemVersionId()
                    );
                }
            }

            String idempotencyKey =
                    buildIdempotencyKey(
                            invoiceId,
                            entitlement
                    );

            String itemCorrelationId =
                    normalizeCorrelationId(
                            correlationId,
                            invoiceId
                    )
                            + "-"
                            + entitlement.invoiceEntityId()
                            .toString()
                            .substring(0, 8);

            CreateClientInventoryItemRequest request =
                    buildRequest(
                            invoice,
                            entitlement,
                            mapping,
                            paymentTransactionId,
                            actorId,
                            idempotencyKey
                    );

            log.info(
                    "[inventory-provisioning] step=api_request "
                            + "invoiceId={} invoiceEntityId={} "
                            + "clientRoleId={} actorId={} locationId={} "
                            + "applicationId={} correlationId={} "
                            + "idempotencyKey={} payload={}",
                    invoiceId,
                    entitlement.invoiceEntityId(),
                    invoice.clientRoleId(),
                    actorId,
                    locationId,
                    applicationId,
                    itemCorrelationId,
                    idempotencyKey,
                    toJsonForLog(request)
            );

            UUID clientInventoryItemId =
                    apiClient.createInventory(
                            invoice.clientRoleId(),
                            actorId,
                            locationId,
                            applicationId,
                            itemCorrelationId,
                            request
                    );

            log.info(
                    "[inventory-provisioning] step=api_response "
                            + "outcome=created invoiceId={} "
                            + "invoiceEntityId={} itemVersionId={} "
                            + "clientInventoryItemId={} correlationId={} "
                            + "idempotencyKey={}",
                    invoiceId,
                    entitlement.invoiceEntityId(),
                    entitlement.itemVersionId(),
                    clientInventoryItemId,
                    itemCorrelationId,
                    idempotencyKey
            );

            created.add(
                    new ProvisionedInventoryItem(
                            entitlement.invoiceEntityId(),
                            entitlement.itemVersionId(),
                            mapping == null ? null : mapping.serviceMappingId(),
                            mapping == null ? null : mapping.redemptionRuleId(),
                            clientInventoryItemId,
                            entitlement.quantity(),
                            idempotencyKey
                    )
            );
        }

        return new InventoryProvisioningResult(
                invoice.invoiceId(),
                invoice.clientRoleId(),
                invoiceEntities.size(),
                entitlements.size(),
                created.size(),
                skipped,
                List.copyOf(created)
        );
    }

    private String toJsonForLog(Object value) {
        if (value == null) {
            return "null";
        }

        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            log.warn(
                    "[inventory-provisioning] step=request_serialization "
                            + "outcome=fallback requestType={} message={}",
                    value.getClass().getName(),
                    exception.getMessage()
            );
            return String.valueOf(value);
        }
    }

    private List<ItemEntitlement> expandEntitlements(
            List<InvoiceEntityLine> invoiceEntities) {

        List<ItemEntitlement> entitlements =
                new ArrayList<>();

        for (InvoiceEntityLine entity : invoiceEntities) {
            switch (entity.entityType()) {
                case "ITEM" ->
                        entitlements.add(
                                repository.loadDirectItem(entity)
                        );

                case "BUNDLE" ->
                        entitlements.addAll(
                                repository.expandPackage(entity)
                        );

                default -> log.debug(
                        "Invoice entity is not inventory-bearing and "
                                + "will be ignored. "
                                + "invoiceEntityId={}, entityType={}",
                        entity.invoiceEntityId(),
                        entity.entityType()
                );
            }
        }

        return entitlements;
    }

    private CreateClientInventoryItemRequest buildRequest(
            InvoiceHeader invoice,
            ItemEntitlement entitlement,
            MappingContext mapping,
            UUID paymentTransactionId,
            UUID actorId,
            String idempotencyKey) {

        Map<String, Object> metadata =
                new LinkedHashMap<>();

        metadata.put("source", "TRANSACTION_FINALIZE");
        metadata.put("invoiceId", invoice.invoiceId());
        metadata.put("invoiceNumber", invoice.invoiceNumber());
        metadata.put(
                "invoiceEntityId",
                entitlement.invoiceEntityId()
        );
        metadata.put(
                "sourceEntityType",
                entitlement.sourceEntityType()
        );
        metadata.put(
                "sourceEntityId",
                entitlement.sourceEntityId()
        );
        metadata.put(
                "sourceEntityVersionId",
                entitlement.sourceEntityVersionId()
        );
        metadata.put(
                "paymentTransactionId",
                paymentTransactionId
        );
        metadata.put(
                "serviceMappingId",
                mapping == null ? null : mapping.serviceMappingId()
        );

        if (entitlement.packageId() != null) {
            metadata.put(
                    "sourcePackageId",
                    entitlement.packageId()
            );
            metadata.put(
                    "sourcePackageVersionId",
                    entitlement.packageVersionId()
            );
            metadata.put(
                    "sourcePackageItemId",
                    entitlement.packageItemId()
            );
        }

        metadata.values().removeIf(Objects::isNull);

        UUID applicationId =
                mapping != null && mapping.applicationId() != null
                        ? mapping.applicationId()
                        : entitlement.applicationId();

        return new CreateClientInventoryItemRequest(
                null,
                invoice.clientRoleId(),
                applicationId,

                entitlement.itemId(),
                entitlement.itemVersionId(),
                entitlement.itemCode(),
                entitlement.itemName(),
                entitlement.itemDescription(),

                requiredProperty(
                        properties.inventorySourceTypeId(),
                        "inventorySourceTypeId"
                ),
                requiredProperty(
                        properties.originalInventoryValueTypeId(),
                        "originalInventoryValueTypeId"
                ),
                requiredProperty(
                        properties.currentInventoryValueTypeId(),
                        "currentInventoryValueTypeId"
                ),
                requiredProperty(
                        properties.inventoryItemStatusId(),
                        "inventoryItemStatusId"
                ),
                requiredProperty(
                        properties.inventoryUnitTypeId(),
                        "inventoryUnitTypeId"
                ),

                mapping == null ? null : mapping.moduleId(),
                mapping == null ? null : mapping.serviceKindLookupId(),
                mapping == null ? null : mapping.serviceCategoryId(),
                mapping == null ? null : mapping.serviceSubcategoryId(),
                mapping == null ? null : mapping.serviceTypeId(),
                null,
                mapping == null ? null : mapping.serviceMappingId(),
                mapping == null ? null : mapping.entitlementTierId(),

                mapping == null ? null : mapping.defaultDurationMinutes(),
                normalizeQuantity(entitlement.quantity()),

                invoice.levelId(),
                invoice.levelId(),

                mapping == null ? null : mapping.redemptionRuleId(),

                "Inventory created from finalized invoice "
                        + invoice.invoiceNumber(),
                metadata,
                idempotencyKey,
                INVENTORY_CREATED_BY
        );
    }

    private UUID requiredProperty(
            UUID value,
            String name) {
        if (value == null) {
            throw new InventoryProvisioningException(
                    "Missing integration.client-inventory."
                            + name
                            + " configuration."
            );
        }
        return value;
    }

    private BigDecimal normalizeQuantity(
            BigDecimal value) {

        if (value == null || value.signum() <= 0) {
            throw new InventoryProvisioningException(
                    "Inventory quantity must be greater than zero."
            );
        }

        return value.stripTrailingZeros();
    }

	/*
	 * private String buildIdempotencyKey( UUID invoiceId, ItemEntitlement
	 * entitlement) {
	 * 
	 * String raw = String.join( "|", invoiceId.toString(),
	 * entitlement.invoiceEntityId().toString(),
	 * entitlement.itemVersionId().toString(), entitlement.packageItemId() == null ?
	 * "-" : entitlement.packageItemId().toString() );
	 * 
	 * return "finalize-inventory-" + sha256(raw).substring(0, 32); }
	 */
    
    private String buildIdempotencyKey(
            UUID invoiceId,
            ItemEntitlement entitlement) {

        String raw = String.join(
                "|",
                invoiceId.toString(),
                entitlement.invoiceEntityId().toString(),
                entitlement.itemVersionId().toString(),
                entitlement.packageItemId() == null
                        ? "-"
                        : entitlement.packageItemId().toString()
        );

        return "finalize-inventory-"
                + sha256(raw).substring(0, 32);
    }

    private String sha256(String value) {
        try {
            MessageDigest digest =
                    MessageDigest.getInstance("SHA-256");

            byte[] hash = digest.digest(
                    value.getBytes(StandardCharsets.UTF_8)
            );

            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(
                    "SHA-256 is unavailable.",
                    ex
            );
        }
    }

    private String normalizeCorrelationId(
            String correlationId,
            UUID invoiceId) {

        if (correlationId == null
                || correlationId.isBlank()) {
            return "finalize-inventory-"
                    + invoiceId.toString()
                    .substring(0, 8);
        }

        String normalized = correlationId.trim();

        return normalized.length() <= 80
                ? normalized
                : normalized.substring(0, 80);
    }
}
