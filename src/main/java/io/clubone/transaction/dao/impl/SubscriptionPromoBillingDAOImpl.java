package io.clubone.transaction.dao.impl;

import io.clubone.transaction.dao.SubscriptionPromoBillingDAO;
import io.clubone.transaction.v2.vo.SubscriptionBillingPromotionDTO;
import io.clubone.transaction.v2.vo.SubscriptionPlanPromoDTO;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.CollectionUtils;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Types;
import java.util.List;
import java.util.UUID;

@Repository
public class SubscriptionPromoBillingDAOImpl implements SubscriptionPromoBillingDAO {

    private final JdbcTemplate cluboneJdbcTemplate;

    public SubscriptionPromoBillingDAOImpl(JdbcTemplate cluboneJdbcTemplate) {
        this.cluboneJdbcTemplate = cluboneJdbcTemplate;
    }

    private static final String SQL_INSERT_PLAN_PROMO = """
        INSERT INTO client_subscription_billing.subscription_plan_promo
        (
            subscription_plan_id,
            promotion_version_id,
            promotion_effect_id,
            cycle_start,
            cycle_end,
            price_cycle_band_id,
            is_active,
            created_by,
            modified_by
        )
        VALUES (?, ?, ?, ?, ?, ?, COALESCE(?, true), ?, ?)
        """;

    private static final String SQL_INSERT_BILLING_PROMO = """
        INSERT INTO client_subscription_billing.subscription_invoice_promotion
        (
            subscription_invoice_schedule_id,
            promotion_version_id,
            promotion_effect_id,
            amount_applied
        )
        VALUES (?, ?, ?, ?)
        """;

    @Override
    @Transactional
    public int insertSubscriptionPlanPromos(UUID subscriptionPlanId, UUID actorId, List<SubscriptionPlanPromoDTO> promos) {
        if (subscriptionPlanId == null) {
            throw new IllegalArgumentException("subscriptionPlanId is required");
        }
        if (actorId == null) {
            throw new IllegalArgumentException("actorId is required");
        }
        if (CollectionUtils.isEmpty(promos)) return 0;

        int[] counts = cluboneJdbcTemplate.batchUpdate(SQL_INSERT_PLAN_PROMO, new BatchPreparedStatementSetter() {
            @Override
            public void setValues(PreparedStatement ps, int i) throws SQLException {
                SubscriptionPlanPromoDTO p = promos.get(i);

                ps.setObject(1, subscriptionPlanId, Types.OTHER);                 // subscription_plan_id
                ps.setObject(2, p.getPromotionVersionId(), Types.OTHER);         // promotion_version_id

                if (p.getPromotionEffectId() != null) ps.setObject(3, p.getPromotionEffectId(), Types.OTHER);
                else ps.setNull(3, Types.OTHER);                                  // promotion_effect_id nullable

                ps.setInt(4, p.getCycleStart());                                  // cycle_start

                if (p.getCycleEnd() != null) ps.setInt(5, p.getCycleEnd());
                else ps.setNull(5, Types.INTEGER);                                // cycle_end nullable

                if (p.getPriceCycleBandId() != null) ps.setObject(6, p.getPriceCycleBandId(), Types.OTHER);
                else ps.setNull(6, Types.OTHER);                                  // price_cycle_band_id nullable

                if (p.getIsActive() != null) ps.setBoolean(7, p.getIsActive());
                else ps.setNull(7, Types.BOOLEAN);                                // defaulted to true via COALESCE in SQL

                ps.setObject(8, actorId, Types.OTHER);                             // created_by
                ps.setObject(9, actorId, Types.OTHER);                             // modified_by
            }

            @Override
            public int getBatchSize() {
                return promos.size();
            }
        });

        return sum(counts);
    }

    @Override
    @Transactional
    public int insertSubscriptionBillingPromotions(UUID subscriptionBillingHistoryId, List<SubscriptionBillingPromotionDTO> promos) {
        if (subscriptionBillingHistoryId == null) {
            throw new IllegalArgumentException("subscriptionBillingHistoryId is required");
        }
        if (CollectionUtils.isEmpty(promos)) return 0;

        int[] counts = cluboneJdbcTemplate.batchUpdate(SQL_INSERT_BILLING_PROMO, new BatchPreparedStatementSetter() {
            @Override
            public void setValues(PreparedStatement ps, int i) throws SQLException {
                SubscriptionBillingPromotionDTO p = promos.get(i);

                ps.setObject(1, subscriptionBillingHistoryId, Types.OTHER);        // subscription_billing_history_id
                ps.setObject(2, p.getPromotionVersionId(), Types.OTHER);          // promotion_version_id

                if (p.getPromotionEffectId() != null) ps.setObject(3, p.getPromotionEffectId(), Types.OTHER);
                else ps.setNull(3, Types.OTHER);                                   // promotion_effect_id nullable

                if (p.getAmountApplied() != null) ps.setBigDecimal(4, p.getAmountApplied());
                else ps.setNull(4, Types.NUMERIC);                                 // amount_applied nullable
            }

            @Override
            public int getBatchSize() {
                return promos.size();
            }
        });

        return sum(counts);
    }

    private int sum(int[] counts) {
        int total = 0;
        if (counts != null) {
            for (int c : counts) total += (c > 0 ? c : 0);
        }
        return total;
    }
}

