package io.clubone.transaction.pos.catalog;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.clubone.transaction.dao.TransactionDAO;
import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class PosAgreementCatalogService {

	private final PosAgreementCatalogDao posAgreementCatalogDao;
	private final TransactionDAO transactionDAO;
	private final ObjectMapper objectMapper;

	public PosCatalogResponseDTO buildCatalog(UUID levelIdOrReference, LocalDate asOf) {
		if (levelIdOrReference == null) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "levelId is required");
		}
		if (asOf == null) {
			asOf = LocalDate.now();
		}
		UUID levelId = transactionDAO.resolveLevelIdForInvoice(levelIdOrReference)
				.orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST,
						"Unknown levelId (expected locations.levels.level_id or reference_entity_id): "
								+ levelIdOrReference));

		List<PosCatalogAgreementDTO> agreements = new ArrayList<>();
		for (PosAgreementCatalogDao.AgreementCatalogRow ar : posAgreementCatalogDao.findAgreementsForLevel(levelId,
				asOf)) {
			JsonNode aConfig = parseConfig(ar.configJson());
			List<PosCatalogBundleDTO> bundles = new ArrayList<>();
			for (UUID bundleId : posAgreementCatalogDao.findBundleIdsForAgreementVersion(ar.agreementVersionId())) {
				PosAgreementCatalogDao.BundleCatalogRow br = posAgreementCatalogDao.findBundleWithVersionAtLevel(bundleId,
						levelId, asOf);
				if (br == null || br.bundleVersionId() == null) {
					continue;
				}
				List<PosCatalogItemDTO> items = new ArrayList<>();
				for (PosAgreementCatalogDao.BundleItemRow bir : posAgreementCatalogDao.findBundleItems(bundleId)) {
					PosAgreementCatalogDao.ItemVersionRow iv = posAgreementCatalogDao.findItemVersion(bir.itemId(), asOf);
					if (iv == null || iv.itemVersionId() == null) {
						continue;
					}
					items.add(new PosCatalogItemDTO(iv.itemId(), iv.itemVersionId(), iv.itemName(),
							parseConfig(iv.configJson()), bir.itemQuantity()));
				}
				bundles.add(new PosCatalogBundleDTO(br.bundleId(), br.bundleVersionId(), br.bundleName(),
						parseConfig(br.configJson()), items));
			}
			agreements.add(new PosCatalogAgreementDTO(ar.agreementId(), ar.agreementVersionId(), ar.agreementName(),
					aConfig, bundles));
		}
		return new PosCatalogResponseDTO(asOf, levelId, agreements);
	}

	private JsonNode parseConfig(String json) {
		if (json == null || json.isBlank()) {
			return objectMapper.createObjectNode();
		}
		try {
			return objectMapper.readTree(json);
		} catch (Exception e) {
			return objectMapper.createObjectNode();
		}
	}
}
