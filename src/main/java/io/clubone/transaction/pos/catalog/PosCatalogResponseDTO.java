package io.clubone.transaction.pos.catalog;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class PosCatalogResponseDTO {
	private LocalDate asOf;
	private UUID levelId;
	private List<PosCatalogAgreementDTO> agreements;
}
