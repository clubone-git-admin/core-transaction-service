package io.clubone.transaction.pos.catalog;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import lombok.Data;

/**
 * Fetches POS agreement catalog from the vendor HTTP API (no DB). Used when denormalizing
 * purchase snapshot lines to agreement/bundle/item version ids and display names.
 * <p>
 * Expected JSON shape (see {@code GET /vendors/api/pos/agreement/catalog?levelId=…}): {@code categories[]} →
 * {@code agreements[]} → {@code bundles[]} → {@code items[]} with {@code agreementId}, {@code versionId},
 * {@code agreementName}, {@code bundleId}, {@code bundleName}, {@code bundleItemId}, {@code itemId},
 * {@code itemName}, {@code itemFeeTypeId}, {@code keyItem}. The request {@code levelId} may differ from the
 * response body {@code levelId} when the vendor resolves a reference level to a canonical location.
 */
@Component
public class VendorPosAgreementCatalogClient {

	private static final Logger log = LoggerFactory.getLogger(VendorPosAgreementCatalogClient.class);

	private final RestTemplate restTemplate;

	@Value("${clubone.vendor.pos-agreement-catalog-url:http://localhost:8011/vendors/api/pos/agreement/catalog}")
	private String catalogUrl;

	public VendorPosAgreementCatalogClient(@Qualifier("userAccessRestTemplate") RestTemplate restTemplate) {
		this.restTemplate = restTemplate;
	}

	/**
	 * GET catalog URL with {@code levelId} and optional {@code asOfDate}, then locate agreement + bundle.
	 *
	 * @param packageItemIdOrBundleIdOrBundleItemId quote {@code packageItemId}: {@code bundleId} or
	 *        {@code bundleItemId}
	 */
	public Optional<VendorPosCatalogSlice.VendorAgreementBundleSlice> fetchAgreementBundle(UUID levelId,
			LocalDate asOf, UUID agreementId,
			UUID packageItemIdOrBundleIdOrBundleItemId) {
		if (levelId == null || agreementId == null || packageItemIdOrBundleIdOrBundleItemId == null) {
			return Optional.empty();
		}
		if (catalogUrl == null || catalogUrl.isBlank()) {
			log.warn("[vendor-pos-catalog] outcome=skip reason=blank_catalog_url");
			return Optional.empty();
		}
		UriComponentsBuilder b = UriComponentsBuilder.fromUriString(Objects.requireNonNull(catalogUrl, "catalogUrl"))
				.queryParam("levelId", levelId);
		if (asOf != null) {
			b.queryParam("asOfDate", asOf.toString());
		}
		String url = b.encode().toUriString();
		try {
			ResponseEntity<VendorPosCatalogResponse> res = restTemplate.getForEntity(url, VendorPosCatalogResponse.class);
			if (!res.getStatusCode().is2xxSuccessful() || res.getBody() == null) {
				log.warn("[vendor-pos-catalog] outcome=empty_http status={} levelId={}", res.getStatusCode(), levelId);
				return Optional.empty();
			}
			return extractSlice(res.getBody(), agreementId, packageItemIdOrBundleIdOrBundleItemId);
		} catch (RestClientException ex) {
			log.warn("[vendor-pos-catalog] outcome=fail levelId={} url={} reason={}", levelId, url, ex.toString());
			return Optional.empty();
		}
	}

	private static Optional<VendorPosCatalogSlice.VendorAgreementBundleSlice> extractSlice(VendorPosCatalogResponse body,
			UUID agreementId,
			UUID packageItemIdOrBundleIdOrBundleItemId) {
		Optional<VendorPosCatalogSlice.VendorAgreementBundleSlice> byBundleId = extractByBundleId(body, agreementId,
				packageItemIdOrBundleIdOrBundleItemId);
		if (byBundleId.isPresent()) {
			return byBundleId;
		}
		Optional<VendorPosCatalogSlice.VendorAgreementBundleSlice> byBundleItem = extractByBundleItemId(body,
				agreementId, packageItemIdOrBundleIdOrBundleItemId);
		if (byBundleItem.isPresent()) {
			return byBundleItem;
		}
		return extractByBundleVersionId(body, agreementId, packageItemIdOrBundleIdOrBundleItemId);
	}

	private static List<VendorAgreementDTO> flattenAgreements(VendorPosCatalogResponse body) {
		List<VendorAgreementDTO> flat = new ArrayList<>();
		if (body.getCategories() != null) {
			for (VendorCategoryDTO cat : body.getCategories()) {
				if (cat != null && cat.getAgreements() != null) {
					flat.addAll(cat.getAgreements());
				}
			}
		}
		if (flat.isEmpty() && body.getAgreements() != null) {
			flat.addAll(body.getAgreements());
		}
		return flat;
	}

	private static Optional<VendorPosCatalogSlice.VendorAgreementBundleSlice> extractByBundleId(
			VendorPosCatalogResponse body,
			UUID agreementId, UUID bundleId) {
		for (VendorAgreementDTO a : flattenAgreements(body)) {
			if (a == null || a.getAgreementId() == null || !agreementId.equals(a.getAgreementId())) {
				continue;
			}
			if (a.getBundles() == null) {
				return Optional.empty();
			}
			for (VendorBundleDTO bundle : a.getBundles()) {
				if (bundle == null || bundle.getBundleId() == null || !bundleId.equals(bundle.getBundleId())) {
					continue;
				}
				return Optional.of(buildSlice(a, bundle));
			}
			return Optional.empty();
		}
		return Optional.empty();
	}

	private static Optional<VendorPosCatalogSlice.VendorAgreementBundleSlice> extractByBundleItemId(
			VendorPosCatalogResponse body,
			UUID agreementId, UUID bundleItemId) {
		for (VendorAgreementDTO a : flattenAgreements(body)) {
			if (a == null || a.getAgreementId() == null || !agreementId.equals(a.getAgreementId())) {
				continue;
			}
			if (a.getBundles() == null) {
				continue;
			}
			for (VendorBundleDTO bundle : a.getBundles()) {
				if (bundle == null || bundle.getItems() == null) {
					continue;
				}
				for (VendorItemDTO it : bundle.getItems()) {
					if (it != null && Objects.equals(bundleItemId, it.getBundleItemId())) {
						return Optional.of(buildSlice(a, bundle));
					}
				}
			}
		}
		return Optional.empty();
	}

	/**
	 * Some quotes send {@code packageVersionId} (same as bundle {@code versionId} / agreement
	 * {@code packageVersionId} in the catalog) instead of {@code bundleId} or {@code bundleItemId}.
	 */
	private static Optional<VendorPosCatalogSlice.VendorAgreementBundleSlice> extractByBundleVersionId(
			VendorPosCatalogResponse body, UUID agreementId, UUID bundleVersionId) {
		for (VendorAgreementDTO a : flattenAgreements(body)) {
			if (a == null || a.getAgreementId() == null || !agreementId.equals(a.getAgreementId())) {
				continue;
			}
			if (a.getBundles() == null) {
				continue;
			}
			for (VendorBundleDTO bundle : a.getBundles()) {
				if (bundle != null && bundleVersionId.equals(bundle.getVersionId())) {
					return Optional.of(buildSlice(a, bundle));
				}
			}
		}
		return Optional.empty();
	}

	private static VendorPosCatalogSlice.VendorAgreementBundleSlice buildSlice(VendorAgreementDTO a,
			VendorBundleDTO bundle) {
		List<VendorPosCatalogSlice.VendorCatalogItemRow> items = new ArrayList<>();
		if (bundle.getItems() != null) {
			for (VendorItemDTO it : bundle.getItems()) {
				if (it == null || it.getItemId() == null || it.getVersionId() == null) {
					continue;
				}
				String name = it.getItemName() != null ? it.getItemName().trim() : "";
				items.add(new VendorPosCatalogSlice.VendorCatalogItemRow(it.getItemId(), it.getVersionId(), name,
						it.getItemFeeTypeId(),
						it.getKeyItem(), it.getBundleItemId()));
			}
		}
		String agreementName = a.getAgreementName() != null ? a.getAgreementName().trim() : null;
		String bundleName = bundle.getBundleName() != null ? bundle.getBundleName().trim() : null;
		return new VendorPosCatalogSlice.VendorAgreementBundleSlice(
				a.getVersionId(),
				agreementName,
				bundle.getVersionId(),
				bundleName,
				items);
	}

	@Data
	@JsonIgnoreProperties(ignoreUnknown = true)
	private static class VendorPosCatalogResponse {
		private UUID locationId;
		private UUID levelId;
		private String locationName;
		private String asOfDate;
		private List<VendorCategoryDTO> categories;
		/** Present on some deployments when categories are omitted */
		private List<VendorAgreementDTO> agreements;
	}

	@Data
	@JsonIgnoreProperties(ignoreUnknown = true)
	private static class VendorCategoryDTO {
		private UUID categoryId;
		private String name;
		private Integer agreementCount;
		private List<VendorAgreementDTO> agreements;
	}

	@Data
	@JsonIgnoreProperties(ignoreUnknown = true)
	private static class VendorAgreementDTO {
		@JsonProperty("agreementId")
		@JsonAlias("agreement_id")
		private UUID agreementId;
		@JsonProperty("versionId")
		@JsonAlias("version_id")
		private UUID versionId;
		@JsonProperty("agreementName")
		@JsonAlias("agreement_name")
		private String agreementName;
		@JsonProperty("packageVersionId")
		@JsonAlias("package_version_id")
		private UUID packageVersionId;
		private List<VendorBundleDTO> bundles;
	}

	@Data
	@JsonIgnoreProperties(ignoreUnknown = true)
	private static class VendorBundleDTO {
		@JsonProperty("bundleId")
		@JsonAlias("bundle_id")
		private UUID bundleId;
		@JsonProperty("versionId")
		@JsonAlias("version_id")
		private UUID versionId;
		@JsonProperty("bundleName")
		@JsonAlias("bundle_name")
		private String bundleName;
		private List<VendorItemDTO> items;
	}

	@Data
	@JsonIgnoreProperties(ignoreUnknown = true)
	private static class VendorItemDTO {
		@JsonProperty("bundleItemId")
		@JsonAlias("bundle_item_id")
		private UUID bundleItemId;
		@JsonProperty("itemId")
		@JsonAlias("item_id")
		private UUID itemId;
		@JsonProperty("versionId")
		@JsonAlias("version_id")
		private UUID versionId;
		@JsonProperty("itemName")
		@JsonAlias("item_name")
		private String itemName;
		@JsonProperty("itemFeeTypeId")
		@JsonAlias("item_fee_type_id")
		private UUID itemFeeTypeId;
		@JsonProperty("keyItem")
		@JsonAlias("key_item")
		private Boolean keyItem;
	}
}
