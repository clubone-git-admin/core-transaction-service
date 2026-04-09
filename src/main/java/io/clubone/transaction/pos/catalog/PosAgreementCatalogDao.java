package io.clubone.transaction.pos.catalog;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public interface PosAgreementCatalogDao {

	List<AgreementCatalogRow> findAgreementsForLevel(UUID levelId, LocalDate asOf);

	List<UUID> findBundleIdsForAgreementVersion(UUID agreementVersionId);

	BundleCatalogRow findBundleWithVersionAtLevel(UUID bundleId, UUID levelId, LocalDate asOf);

	List<BundleItemRow> findBundleItems(UUID bundleId);

	ItemVersionRow findItemVersion(UUID itemId, LocalDate asOf);

	record AgreementCatalogRow(UUID agreementId, String agreementName, UUID agreementVersionId, String configJson) {
	}

	record BundleCatalogRow(UUID bundleId, String bundleName, UUID bundleVersionId, String configJson) {
	}

	record BundleItemRow(UUID itemId, java.math.BigDecimal itemQuantity) {
	}

	record ItemVersionRow(UUID itemId, UUID itemVersionId, String itemName, String configJson) {
	}
}
