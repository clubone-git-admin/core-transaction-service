package io.clubone.transaction.dao.impl;

import io.clubone.transaction.dao.InvoiceEntityPromotionDAO;
import io.clubone.transaction.v2.vo.SubscriptionBillingPromotionDTO;
import io.clubone.transaction.v2.vo.SubscriptionPlanPromoDTO;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.util.CollectionUtils;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;
import java.util.stream.Collectors;

@Repository
public class InvoiceEntityPromotionDAOImpl implements InvoiceEntityPromotionDAO {

    private final JdbcTemplate cluboneJdbcTemplate;

    public InvoiceEntityPromotionDAOImpl(JdbcTemplate cluboneJdbcTemplate) {
        this.cluboneJdbcTemplate = cluboneJdbcTemplate;
    }

    private static final String SQL_BY_IE_ID = """
        SELECT
            iep.invoice_entity_promotion_id,
            iep.invoice_entity_id,
            iep.promotion_version_id,
            iep.promotion_effect_id,
            iep.promotion_applicability_id,
            iep.promotion_amount
        FROM transactions.invoice_entity_promotion iep
        WHERE iep.invoice_entity_id = ?
          AND COALESCE(iep.is_active, true) = true
        ORDER BY iep.created_on ASC, iep.invoice_entity_promotion_id ASC
        """;

    @Override
    public List<InvoiceEntityPromotionRow> fetchActivePromotionsByInvoiceEntityId(UUID invoiceEntityId) {
        if (invoiceEntityId == null) return Collections.emptyList();

        return cluboneJdbcTemplate.query(SQL_BY_IE_ID, (rs, rowNum) -> mapRow(rs), invoiceEntityId);
    }

    @Override
    public Map<UUID, List<InvoiceEntityPromotionRow>> fetchActivePromotionsByInvoiceEntityIds(List<UUID> invoiceEntityIds) {
        if (CollectionUtils.isEmpty(invoiceEntityIds)) return Collections.emptyMap();

        // Build placeholders (?, ?, ?)
        String placeholders = invoiceEntityIds.stream().map(x -> "?").collect(Collectors.joining(","));
        String sql = String.format("""
        	    SELECT
        	        iep.invoice_entity_promotion_id,
        	        iep.invoice_entity_id,
        	        iep.promotion_version_id,
        	        iep.promotion_effect_id,
        	        iep.promotion_applicability_id,
        	        iep.promotion_amount
        	    FROM transactions.invoice_entity_promotion iep
        	    WHERE iep.invoice_entity_id IN (%s)
        	      AND COALESCE(iep.is_active, true) = true
        	    ORDER BY
        	        iep.invoice_entity_id ASC,
        	        iep.created_on ASC,
        	        iep.invoice_entity_promotion_id ASC
        	    """, placeholders);


        List<InvoiceEntityPromotionRow> all =
                cluboneJdbcTemplate.query(sql, invoiceEntityIds.toArray(), (rs, rowNum) -> mapRow(rs));

        // Group by invoice_entity_id
        return all.stream().collect(Collectors.groupingBy(
                InvoiceEntityPromotionRow::invoiceEntityId,
                LinkedHashMap::new,
                Collectors.toList()
        ));
    }

    @Override
    public List<SubscriptionPlanPromoDTO> toSubscriptionPlanPromos(
            List<InvoiceEntityPromotionRow> promoRows,
            int cycleStart,
            Integer cycleEnd,
            UUID priceCycleBandId
    ) {
        if (promoRows == null || promoRows.isEmpty()) return Collections.emptyList();

        List<SubscriptionPlanPromoDTO> out = new ArrayList<>(promoRows.size());
        for (InvoiceEntityPromotionRow r : promoRows) {
            SubscriptionPlanPromoDTO dto = new SubscriptionPlanPromoDTO();
            dto.setPromotionVersionId(r.promotionVersionId());
            dto.setPromotionEffectId(r.promotionEffectId());
            dto.setCycleStart(cycleStart);
            dto.setCycleEnd(cycleEnd);
            dto.setPriceCycleBandId(priceCycleBandId);
            dto.setIsActive(true);
            out.add(dto);
        }
        return out;
    }

    @Override
    public List<SubscriptionBillingPromotionDTO> toSubscriptionBillingPromotions(List<InvoiceEntityPromotionRow> promoRows) {
        if (promoRows == null || promoRows.isEmpty()) return Collections.emptyList();

        List<SubscriptionBillingPromotionDTO> out = new ArrayList<>(promoRows.size());
        for (InvoiceEntityPromotionRow r : promoRows) {
            SubscriptionBillingPromotionDTO dto = new SubscriptionBillingPromotionDTO();
            dto.setPromotionVersionId(r.promotionVersionId());
            dto.setPromotionEffectId(r.promotionEffectId());

            // Your table expects amount_applied numeric(12,3)
            dto.setAmountApplied(r.promotionAmount() == null ? BigDecimal.ZERO : r.promotionAmount());

            out.add(dto);
        }
        return out;
    }

    private InvoiceEntityPromotionRow mapRow(ResultSet rs) throws SQLException {
        UUID iepId = (UUID) rs.getObject("invoice_entity_promotion_id");
        UUID ieId = (UUID) rs.getObject("invoice_entity_id");
        UUID pvId = (UUID) rs.getObject("promotion_version_id");
        UUID peId = (UUID) rs.getObject("promotion_effect_id");
        UUID paId = (UUID) rs.getObject("promotion_applicability_id");
        BigDecimal amt = rs.getBigDecimal("promotion_amount");

        return new InvoiceEntityPromotionRow(iepId, ieId, pvId, peId, paId, amt);
    }
}

