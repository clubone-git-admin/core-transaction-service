package io.clubone.transaction.dao;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import io.clubone.transaction.v2.vo.PromotionItemEffectDTO;

public interface PromotionEffectDAO {

    // Single item lookup
    PromotionItemEffectDTO fetchEffectByPromotionAndItem(UUID promotionId, UUID itemId, UUID applicationId);

    // Batch lookup (recommended)
    Map<UUID, PromotionItemEffectDTO> fetchEffectsByPromotionForItems(UUID promotionId, Set<UUID> itemIds, UUID applicationId);
}
