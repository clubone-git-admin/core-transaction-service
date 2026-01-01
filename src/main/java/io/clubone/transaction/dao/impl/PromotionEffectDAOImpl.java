package io.clubone.transaction.dao.impl;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import io.clubone.transaction.dao.PromotionEffectDAO;
import io.clubone.transaction.v2.vo.PromotionItemEffectDTO;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;

@Repository
public class PromotionEffectDAOImpl implements PromotionEffectDAO {

    @Autowired
    @Qualifier("cluboneJdbcTemplate")
    private JdbcTemplate cluboneJdbcTemplate;

    private static final class PromotionItemEffectRowMapper implements RowMapper<PromotionItemEffectDTO> {
        @Override
        public PromotionItemEffectDTO mapRow(ResultSet rs, int rowNum) throws SQLException {
            PromotionItemEffectDTO dto = new PromotionItemEffectDTO();
            dto.setPromotionId((UUID) rs.getObject("promotion_id"));
            dto.setPromotionVersionId((UUID) rs.getObject("promotion_version_id"));
            dto.setItemId((UUID) rs.getObject("item_id"));

            dto.setPromotionEffectId((UUID) rs.getObject("promotion_effect_id"));
            dto.setEffectTypeId((UUID) rs.getObject("effect_type_id"));
            dto.setEffectTypeDescription(rs.getString("effect_type_description"));

            dto.setValueAmount(rs.getBigDecimal("value_amount"));
            dto.setValuePercent(rs.getBigDecimal("value_percent"));
            return dto;
        }
    }

    /**
     * Assumptions (adjust if your schema names differ):
     * - promotions.promotion has: promotion_id, application_id, is_active, current_version_id
     * - promotions.promotion_version has: promotion_version_id, is_active
     * - promotions.promotion_entity_scope has:
     *     promotion_entity_scope_id, promotion_version_id, is_active,
     *     entity_scope_type_code, entity_scope_id
     * - promotions.promotion_effects has:
     *     promotion_effect_id, promotion_entity_scope_id, is_active,
     *     effect_type_id, value_amount, value_percent
     * - promotions.lu_effect_type has: effect_type_id, name, is_active
     */
    private static final String SQL_SINGLE_ITEM_EFFECT =
            """
            SELECT
                p.promotion_id,
                pv.promotion_version_id,
                pes.entity_scope_id AS item_id,
                pe.promotion_effect_id,
                pe.effect_type_id,
                et.name AS effect_type_description,
                pe.value_amount,
                pe.value_percent
            FROM promotions.promotion p
            JOIN promotions.promotion_version pv
                ON pv.promotion_version_id = p.current_version_id
               AND pv.is_active = true
            JOIN promotions.promotion_applicability pa
                ON pa.promotion_version_id = pv.promotion_version_id
               AND pa.is_active = true
               AND pa.application_id = p.application_id
            JOIN promotions.promotion_entity_scope pes
                ON pes.promotion_applicability_id = pa.promotion_applicability_id
               AND pes.is_active = true
               AND pes.application_id = p.application_id
            JOIN promotions.lu_entity_scope_type est
                ON est.entity_scope_type_id = pes.entity_scope_type_id
            JOIN promotions.promotion_effects pe
                ON pe.promotion_entity_scope_id = pes.promotion_entity_scope_id
               AND pe.is_active = true
            JOIN promotions.lu_effect_type et
                ON et.effect_type_id = pe.effect_type_id
               AND et.is_active = true
            WHERE p.promotion_id = ?
              AND p.application_id = ?
              AND p.is_active = true
              AND est.code = 'ITEM'
              AND pes.entity_scope_id = ?
            ORDER BY pe.display_order ASC
            LIMIT 1
            """;


    @Override
    public PromotionItemEffectDTO fetchEffectByPromotionAndItem(UUID promotionId, UUID itemId, UUID applicationId) {
        List<PromotionItemEffectDTO> rows = cluboneJdbcTemplate.query(
                SQL_SINGLE_ITEM_EFFECT,
                new PromotionItemEffectRowMapper(),
                promotionId,
                applicationId,
                itemId
        );
        return rows.isEmpty() ? null : rows.get(0);
    }

    @Override
    public Map<UUID, PromotionItemEffectDTO> fetchEffectsByPromotionForItems(
            UUID promotionId, Set<UUID> itemIds, UUID applicationId
    ) {
        if (promotionId == null || itemIds == null || itemIds.isEmpty()) return Collections.emptyMap();

        String placeholders = String.join(",", Collections.nCopies(itemIds.size(), "?"));

        String sql =
            """
            SELECT
                p.promotion_id,
                pv.promotion_version_id,
                pes.entity_scope_id AS item_id,
                pe.promotion_effect_id,
                pe.effect_type_id,
                et.name AS effect_type_description,
                pe.value_amount,
                pe.value_percent
            FROM promotions.promotion p
            JOIN promotions.promotion_version pv
                ON pv.promotion_version_id = p.current_version_id
               AND pv.is_active = true
            JOIN promotions.promotion_applicability pa
                ON pa.promotion_version_id = pv.promotion_version_id
               AND pa.is_active = true
               AND pa.application_id = p.application_id
            JOIN promotions.promotion_entity_scope pes
                ON pes.promotion_applicability_id = pa.promotion_applicability_id
               AND pes.is_active = true
               AND pes.application_id = p.application_id
            JOIN promotions.lu_entity_scope_type est
                ON est.entity_scope_type_id = pes.entity_scope_type_id
            JOIN promotions.promotion_effects pe
                ON pe.promotion_entity_scope_id = pes.promotion_entity_scope_id
               AND pe.is_active = true
            JOIN promotions.lu_effect_type et
                ON et.effect_type_id = pe.effect_type_id
               AND et.is_active = true
            WHERE p.promotion_id = ?
              AND p.application_id = ?
              AND p.is_active = true
              AND est.code = 'ITEM'
              AND pes.entity_scope_id IN (""" + placeholders + """
              )
            ORDER BY pes.entity_scope_id, pe.display_order ASC
            """;

        List<Object> params = new ArrayList<>();
        params.add(promotionId);
        params.add(applicationId);
        params.addAll(itemIds);

        List<PromotionItemEffectDTO> rows =
                cluboneJdbcTemplate.query(sql, new PromotionItemEffectRowMapper(), params.toArray());

        Map<UUID, PromotionItemEffectDTO> map = new HashMap<>();
        for (PromotionItemEffectDTO r : rows) {
            map.putIfAbsent(r.getItemId(), r); // keep first by display_order
        }
        return map;
    }

}

