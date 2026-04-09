package io.clubone.transaction.controller;

import java.time.LocalDate;
import java.util.UUID;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import io.clubone.transaction.pos.catalog.PosAgreementCatalogService;
import io.clubone.transaction.pos.catalog.PosCatalogResponseDTO;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/pos/agreement")
@RequiredArgsConstructor
public class PosAgreementCatalogController {

	private final PosAgreementCatalogService posAgreementCatalogService;

	/**
	 * POS catalog: agreements available at the level, each with the effective published version
	 * (prefers {@code is_current}, then latest {@code valid_from}), bundles linked on that agreement version,
	 * and bundle items with their effective item versions. Version-scoped {@code config} only.
	 */
	@GetMapping("/catalog")
	public ResponseEntity<PosCatalogResponseDTO> catalog(@RequestParam UUID levelId,
			@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate asOf) {
		LocalDate effective = asOf != null ? asOf : LocalDate.now();
		return ResponseEntity.ok(posAgreementCatalogService.buildCatalog(levelId, effective));
	}
}
