package io.clubone.transaction.dao;

import java.util.Optional;
import java.util.UUID;

import io.clubone.transaction.v2.vo.EntityLevelInfoDTO;

public interface EntityLookupDao {

	/** Resolve entity name + level name given raw ids. */
	Optional<EntityLevelInfoDTO> resolveEntityAndLevel(UUID entityTypeId, UUID entityId, UUID levelId);

	/**
	 * Resolve from an invoice_entity row (uses plan template level, falling back to
	 * invoice level).
	 */
	Optional<EntityLevelInfoDTO> resolveFromInvoiceEntity(UUID invoiceEntityId);
}
