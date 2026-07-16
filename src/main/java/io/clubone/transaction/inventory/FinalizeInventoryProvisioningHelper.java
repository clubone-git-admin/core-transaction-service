package io.clubone.transaction.inventory;

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
            String correlationId) {

        Objects.requireNonNull(
                invoiceId,
                "invoiceId is required"
        );
        Objects.requireNonNull(
                actorId,
                "actorId is required"
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

        List<ProvisionedInventoryItem> created =
                new ArrayList<>();
        int skipped = 0;

        for (ItemEntitlement entitlement : entitlements) {
            MappingContext mapping =
                    repository.resolveMapping(
                            entitlement.itemVersionId()
                    );

            if (mapping == null) {
                if (properties.failOnMissingMapping()) {
                    throw new InventoryProvisioningException(
                            "No active scheduling inventory mapping "
                                    + "was found for itemVersionId="
                                    + entitlement.itemVersionId()
                    );
                }

                skipped++;
                log.info(
                        "Skipping inventory creation because no "
                                + "mapping exists. invoiceId={}, "
                                + "invoiceEntityId={}, itemVersionId={}",
                        invoiceId,
                        entitlement.invoiceEntityId(),
                        entitlement.itemVersionId()
                );
                continue;
            }

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

            UUID clientInventoryItemId =
                    apiClient.createInventory(
                            invoice.clientRoleId(),
                            actorId,
                            invoice.levelId(),
                            itemCorrelationId,
                            request
                    );

            created.add(
                    new ProvisionedInventoryItem(
                            entitlement.invoiceEntityId(),
                            entitlement.itemVersionId(),
                            mapping.serviceMappingId(),
                            mapping.redemptionRuleId(),
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
                mapping.serviceMappingId()
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
                mapping.applicationId() != null
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

                mapping.moduleId(),
                mapping.serviceKindLookupId(),
                mapping.serviceCategoryId(),
                mapping.serviceSubcategoryId(),
                mapping.serviceTypeId(),
                null,
                mapping.serviceMappingId(),
                mapping.entitlementTierId(),

                mapping.defaultDurationMinutes(),
                normalizeQuantity(entitlement.quantity()),

                invoice.levelId(),
                invoice.levelId(),

                mapping.redemptionRuleId(),

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
