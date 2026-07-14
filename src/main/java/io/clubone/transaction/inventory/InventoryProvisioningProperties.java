package io.clubone.transaction.inventory;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.net.URI;
import java.time.Duration;
import java.util.UUID;

@ConfigurationProperties(prefix = "integration.client-inventory")
public record InventoryProvisioningProperties(
        URI baseUrl,
        UUID inventorySourceTypeId,
        UUID originalInventoryValueTypeId,
        UUID currentInventoryValueTypeId,
        UUID inventoryItemStatusId,
        UUID inventoryUnitTypeId,
        Duration connectTimeout,
        Duration readTimeout,
        boolean failOnMissingMapping,
        boolean requireRedemptionRule
) {
    public InventoryProvisioningProperties {
        connectTimeout = connectTimeout == null
                ? Duration.ofSeconds(3)
                : connectTimeout;
        readTimeout = readTimeout == null
                ? Duration.ofSeconds(15)
                : readTimeout;
    }
}
