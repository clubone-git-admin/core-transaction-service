package io.clubone.transaction.pos.catalog;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * HTTP vendor POS catalog subset used for purchase snapshot denormalization.
 */
public final class VendorPosCatalogSlice {

	private VendorPosCatalogSlice() {
	}

	/**
	 * @param itemEntityId catalog {@code itemId}
	 * @param bundleItemId POS {@code bundleItemId} (quote {@code packageItemId} often matches this, not
	 *        {@code bundleId})
	 */
	public record VendorCatalogItemRow(UUID itemEntityId, UUID itemVersionId, String itemDisplayName,
			UUID itemFeeTypeId, Boolean keyItem, UUID bundleItemId) {

		public boolean feeLikeCatalogItem() {
			if (itemFeeTypeId != null) {
				return true;
			}
			if (itemDisplayName == null || itemDisplayName.isBlank()) {
				return false;
			}
			String n = itemDisplayName.toLowerCase(Locale.ROOT);
			return n.contains("initiation") || n.contains("enrollment") || n.contains("joining fee")
					|| n.contains("signup fee") || n.contains("sign-up fee");
		}
	}

	public record VendorAgreementBundleSlice(UUID agreementVersionId, String agreementDisplayName,
			UUID bundleVersionId, String bundleDisplayName, List<VendorCatalogItemRow> bundleItems) {
	}
}
