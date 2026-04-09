package io.clubone.transaction.pos.catalog;

import java.math.BigDecimal;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class PosCatalogItemDTO {
	private UUID entityId;
	private UUID versionId;
	private String name;
	private JsonNode config;
	private BigDecimal quantityInBundle;
}
