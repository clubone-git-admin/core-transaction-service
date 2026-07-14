package io.clubone.transaction.inventory;

import lombok.RequiredArgsConstructor;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import static io.clubone.transaction.inventory.InventoryProvisioningModels.*;

@Repository
@RequiredArgsConstructor
public class InvoiceInventoryProvisioningRepository {

    private final NamedParameterJdbcTemplate jdbc;
    private static final UUID TEST_REDEMPTION_RULE_ID =
            UUID.fromString(
                    "1b665963-9cbf-4fb8-b3b1-6b44042808df"
            );

    public InvoiceHeader loadInvoice(UUID invoiceId) {
        try {
            return jdbc.queryForObject("""
                    SELECT
                        invoice.invoice_id,
                        invoice.invoice_number,
                        invoice.client_role_id,
                        invoice.level_id
                    FROM transactions.invoice invoice
                    WHERE invoice.invoice_id = :invoiceId
                      AND invoice.is_active = true
                    """,
                    new MapSqlParameterSource("invoiceId", invoiceId),
                    (rs, rowNum) -> new InvoiceHeader(
                            uuid(rs, "invoice_id"),
                            rs.getString("invoice_number"),
                            uuid(rs, "client_role_id"),
                            uuid(rs, "level_id")
                    ));
        } catch (EmptyResultDataAccessException ex) {
            throw new InventoryProvisioningException(
                    "Invoice was not found or is inactive: "
                            + invoiceId,
                    ex
            );
        }
    }

    /**
     * Only root invoice entities are loaded.
     *
     * Bundle child item rows are intentionally not loaded here because a
     * bundle is expanded from package.package_item. This prevents duplicate
     * inventory when the invoice also stores descriptive child rows.
     */
    public List<InvoiceEntityLine> loadRootInvoiceEntities(
            UUID invoiceId) {

        return jdbc.query("""
                SELECT
                    entity.invoice_entity_id,
                    entity.parent_invoice_entity_id,
                    upper(entity_type_lookup.entity_type) AS entity_type,
                    entity.entity_id,
                    entity.entity_version_id,
                    entity.entity_description,
                    coalesce(entity.quantity, 1)::numeric
                        AS quantity
                FROM transactions.invoice_entity entity
                JOIN transactions.lu_entity_type entity_type_lookup
                  ON entity_type_lookup.entity_type_id = entity.entity_type_id
                 AND coalesce(entity_type_lookup.is_active, true) = true
                WHERE entity.invoice_id = :invoiceId
                  AND entity.is_active = true
                  AND entity.parent_invoice_entity_id IS NULL
                ORDER BY entity.created_on, entity.invoice_entity_id
                """,
                new MapSqlParameterSource("invoiceId", invoiceId),
                (rs, rowNum) -> new InvoiceEntityLine(
                        uuid(rs, "invoice_entity_id"),
                        uuid(rs, "parent_invoice_entity_id"),
                        normalizeEntityType(
                                rs.getString("entity_type")
                        ),
                        uuid(rs, "entity_id"),
                        uuid(rs, "entity_version_id"),
                        rs.getString("entity_description"),
                        decimal(rs, "quantity")
                ));
    }

    public ItemEntitlement loadDirectItem(
            InvoiceEntityLine entity) {

        if (entity.entityVersionId() == null) {
            throw new InventoryProvisioningException(
                    "Item invoice entity "
                            + entity.invoiceEntityId()
                            + " does not contain entityVersionId."
            );
        }

        try {
            return jdbc.queryForObject("""
                    SELECT
                        version.item_version_id,
                        version.item_id,
                        version.application_id,
                        coalesce(
                            nullif(item.item_name, ''),
                            nullif(version.description, ''),
                            :fallbackName,
                            version.item_version_id::text
                        ) AS item_name,
                        coalesce(
                            nullif(item.item_name, ''),
                            :fallbackName,
                            version.item_version_id::text
                        ) AS item_code,
                        coalesce(
                            nullif(version.description, ''),
                            nullif(item.description, ''),
                            :fallbackDescription
                        ) AS item_description
                    FROM items.item_version version
                    JOIN items.item item
                      ON item.item_id = version.item_id
                     AND item.is_active = true
                    WHERE version.item_version_id = :itemVersionId
                      AND version.is_active = true
                    """,
                    new MapSqlParameterSource()
                            .addValue(
                                    "itemVersionId",
                                    entity.entityVersionId()
                            )
                            .addValue(
                                    "fallbackName",
                                    defaultText(
                                            entity.description(),
                                            "Purchased item"
                                    )
                            )
                            .addValue(
                                    "fallbackDescription",
                                    entity.description()
                            ),
                    (rs, rowNum) -> new ItemEntitlement(
                            entity.invoiceEntityId(),
                            entity.entityType(),
                            entity.entityId(),
                            entity.entityVersionId(),
                            null,
                            null,
                            null,
                            uuid(rs, "item_id"),
                            uuid(rs, "item_version_id"),
                            uuid(rs, "application_id"),
                            rs.getString("item_code"),
                            rs.getString("item_name"),
                            rs.getString("item_description"),
                            positiveQuantity(entity.quantity())
                    ));
        } catch (EmptyResultDataAccessException ex) {
            throw new InventoryProvisioningException(
                    "Active item version was not found: "
                            + entity.entityVersionId(),
                    ex
            );
        }
    }

    public List<ItemEntitlement> expandPackage(
            InvoiceEntityLine entity) {

        UUID packageId = entity.entityId();
        UUID packageVersionId = entity.entityVersionId();

        if (packageId == null) {
            throw new InventoryProvisioningException(
                    "Bundle invoice entity "
                            + entity.invoiceEntityId()
                            + " does not contain entityId/packageId."
            );
        }

        List<ItemEntitlement> rows = jdbc.query("""
                SELECT
                    package.package_id,
                    coalesce(
                        :packageVersionId,
                        package.current_version_id
                    ) AS package_version_id,
                    package_item.package_item_id,
                    package_item.item_quantity,
                    version.item_version_id,
                    version.item_id,
                    version.application_id,
                    coalesce(
                        nullif(item.item_name, ''),
                        nullif(version.description, ''),
                        version.item_version_id::text
                    ) AS item_name,
                    coalesce(
                        nullif(item.item_name, ''),
                        version.item_version_id::text
                    ) AS item_code,
                    coalesce(
                        nullif(version.description, ''),
                        nullif(item.description, ''),
                        :fallbackDescription
                    ) AS item_description
                FROM package.package package
                JOIN package.package_item package_item
                  ON package_item.package_id = package.package_id
                 AND package_item.is_active = true
                JOIN items.item_version version
                  ON version.item_version_id =
                     package_item.item_version_id
                 AND version.is_active = true
                JOIN items.item item
                  ON item.item_id = version.item_id
                 AND item.is_active = true
                WHERE package.package_id = :packageId
                  AND package.is_active = true
                ORDER BY
                    coalesce(package_item.display_order, 2147483647),
                    package_item.package_item_id
                """,
                new MapSqlParameterSource()
                        .addValue("packageId", packageId)
                        .addValue(
                                "packageVersionId",
                                packageVersionId
                        )
                        .addValue(
                                "fallbackDescription",
                                entity.description()
                        ),
                (rs, rowNum) -> {
                    BigDecimal itemQuantity =
                            BigDecimal.valueOf(
                                    rs.getInt("item_quantity")
                            );

                    return new ItemEntitlement(
                            entity.invoiceEntityId(),
                            entity.entityType(),
                            entity.entityId(),
                            entity.entityVersionId(),
                            uuid(rs, "package_id"),
                            uuid(rs, "package_version_id"),
                            uuid(rs, "package_item_id"),
                            uuid(rs, "item_id"),
                            uuid(rs, "item_version_id"),
                            uuid(rs, "application_id"),
                            rs.getString("item_code"),
                            rs.getString("item_name"),
                            rs.getString("item_description"),
                            positiveQuantity(entity.quantity())
                                    .multiply(itemQuantity)
                    );
                });

        if (rows.isEmpty()) {
            throw new InventoryProvisioningException(
                    "Bundle/package contains no active item: "
                            + packageId
            );
        }

        return rows;
    }

    public MappingContext resolveMapping(UUID itemVersionId) {
        List<MappingContext> mappings = jdbc.query("""
                SELECT
                    mapping.sch_inventory_mapping_id,
                    coalesce(
                        mapping.application_id,
                        item_version.application_id
                    ) AS application_id,
                    coalesce(
                        mapping.module_id,
                        service_type.module_id
                    ) AS module_id,
                    coalesce(
                        mapping.service_kind_lookup_id,
                        service_type.service_kind_lookup_id
                    ) AS service_kind_lookup_id,
                    coalesce(
                        mapping.service_category_id,
                        service_type.service_category_id
                    ) AS service_category_id,
                    coalesce(
                        mapping.service_subcategory_id,
                        service_type.service_subcategory_id
                    ) AS service_subcategory_id,
                    mapping.service_type_id,
                    service_type.default_duration_minutes,
                    mapping.service_tier_id
                        AS entitlement_tier_id,
                    rule.redemption_rule_id,
                    rule.reinstatement_rule_id,
                    mapping.priority
                FROM scheduling.sch_inventory_mapping mapping
                JOIN items.item_version item_version
                  ON item_version.item_version_id =
                     mapping.item_version_id
                JOIN scheduling.sch_service_type service_type
                  ON service_type.sch_service_type_id =
                     mapping.service_type_id
                 AND service_type.is_active = true

                LEFT JOIN LATERAL (
                    SELECT
                        redemption_rule.redemption_rule_id,
                        redemption_rule.reinstatement_rule_id
                    FROM client_inventory
                        .client_inventory_redemption_rule
                        redemption_rule
                    WHERE redemption_rule.is_active = true
                      AND (
                          redemption_rule.service_mapping_id =
                              mapping.sch_inventory_mapping_id
                          OR (
                              redemption_rule.service_mapping_id
                                  IS NULL
                              AND redemption_rule.item_version_id =
                                  mapping.item_version_id
                              AND (
                                  redemption_rule.service_type_id
                                      IS NULL
                                  OR redemption_rule.service_type_id =
                                      mapping.service_type_id
                              )
                          )
                      )
                      AND (
                          redemption_rule.effective_from IS NULL
                          OR redemption_rule.effective_from <= now()
                      )
                      AND (
                          redemption_rule.effective_thru IS NULL
                          OR redemption_rule.effective_thru > now()
                      )
                    ORDER BY
                        CASE
                            WHEN redemption_rule.service_mapping_id =
                                 mapping.sch_inventory_mapping_id
                            THEN 0
                            ELSE 1
                        END,
                        redemption_rule.priority_order,
                        redemption_rule.created_on
                    LIMIT 1
                ) rule ON true

                WHERE mapping.item_version_id = :itemVersionId
                  AND mapping.is_active = true
                  AND (
                      mapping.effective_from IS NULL
                      OR mapping.effective_from <= now()
                  )
                  AND (
                      mapping.effective_to IS NULL
                      OR mapping.effective_to > now()
                  )
                ORDER BY
                    mapping.priority,
                    mapping.mapping_version DESC,
                    mapping.created_on
                LIMIT 2
                """,
                new MapSqlParameterSource(
                        "itemVersionId",
                        itemVersionId
                ),
                this::mapMapping);

        if (mappings.isEmpty()) {
            return null;
        }

        if (mappings.size() > 1
                && mappings.get(0).priority()
                == mappings.get(1).priority()) {
            throw new InventoryProvisioningException(
                    "Multiple active inventory mappings have the "
                            + "same priority for itemVersionId="
                            + itemVersionId
            );
        }

        return mappings.get(0);
    }

    private MappingContext mapMapping(
            ResultSet rs,
            int rowNum) throws SQLException {

        return new MappingContext(
                uuid(rs, "sch_inventory_mapping_id"),
                uuid(rs, "application_id"),
                uuid(rs, "module_id"),
                uuid(rs, "service_kind_lookup_id"),
                uuid(rs, "service_category_id"),
                uuid(rs, "service_subcategory_id"),
                uuid(rs, "service_type_id"),
                integer(rs, "default_duration_minutes"),
                uuid(rs, "entitlement_tier_id"),
                TEST_REDEMPTION_RULE_ID,
               // uuid(rs, "redemption_rule_id"),
                uuid(rs, "reinstatement_rule_id"),
                rs.getInt("priority")
        );
    }

    private String normalizeEntityType(String value) {
        if (value == null) {
            return "";
        }

        String normalized = value.trim()
                .toUpperCase(Locale.ROOT);

        return switch (normalized) {
            case "BUNDLE", "PACKAGE" -> "BUNDLE";
            case "ITEM" -> "ITEM";
            default -> normalized;
        };
    }

    private BigDecimal positiveQuantity(BigDecimal value) {
        if (value == null || value.signum() <= 0) {
            return BigDecimal.ONE;
        }
        return value;
    }

    private String defaultText(
            String value,
            String fallback) {
        return value == null || value.isBlank()
                ? fallback
                : value.trim();
    }

    private UUID uuid(
            ResultSet rs,
            String column) throws SQLException {
        Object value = rs.getObject(column);
        return value == null ? null : (UUID) value;
    }

    private BigDecimal decimal(
            ResultSet rs,
            String column) throws SQLException {
        BigDecimal value = rs.getBigDecimal(column);
        return value == null ? BigDecimal.ZERO : value;
    }

    private Integer integer(
            ResultSet rs,
            String column) throws SQLException {
        return rs.getObject(column) == null
                ? null
                : rs.getInt(column);
    }
}
