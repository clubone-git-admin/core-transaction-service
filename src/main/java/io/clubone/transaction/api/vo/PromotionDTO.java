package io.clubone.transaction.api.vo;

import java.util.List;
import java.util.UUID;
import lombok.Data;

@Data
public class PromotionDTO {

	private UUID promotionId;
	private UUID bundleId;
	private List<CompItemDTO> compItems;

}
