package io.clubone.transaction.subscription.billing.dao.impl;

import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import io.clubone.transaction.subscription.billing.dao.SubscriptionBillingScheduleDAO;
import io.clubone.transaction.subscription.billing.model.CyclePriceProjection;
import io.clubone.transaction.subscription.billing.model.SubscriptionBillingScheduleRow;

import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Types;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@Repository
public class SubscriptionBillingScheduleDAOImpl implements SubscriptionBillingScheduleDAO {

    private final JdbcTemplate cluboneJdbcTemplate;

    public SubscriptionBillingScheduleDAOImpl(JdbcTemplate cluboneJdbcTemplate) {
        this.cluboneJdbcTemplate = cluboneJdbcTemplate;
    }

    @Override
    public UUID billingScheduleStatusId(String statusCode) {
        String sql = """
            select billing_schedule_status_id
            from client_subscription_billing.lu_billing_schedule_status
            where status_code = ?
              and is_active = true
            limit 1
            """;

        return cluboneJdbcTemplate.queryForObject(
                sql,
                (rs, rowNum) -> UUID.fromString(rs.getString("billing_schedule_status_id")),
                statusCode
        );
    }

    @Override
    public int[] batchInsertBillingSchedule(List<SubscriptionBillingScheduleRow> rows) {
        String sql = """
            insert into client_subscription_billing.subscription_billing_schedule (
                billing_schedule_id,
                client_agreement_id,
                subscription_plan_id,
                subscription_instance_id,
                cycle_number,
                billing_period_start,
                billing_period_end,
                billing_date,
                base_amount,
                override_amount,
                system_adjustment_amount,
                manual_adjustment_amount,
                discount_amount,
                tax_amount,
                billing_schedule_status_id,
                price_source_type,
                price_source_id,
                source_event_type,
                source_event_id,
                is_freeze_cycle,
                is_cancellation_cycle,
                is_prorated,
                is_generated,
                is_locked,
                notes,
                invoice_id,
                billed_on,
                created_by
            ) values (
                ?::uuid, ?::uuid, ?::uuid, ?::uuid, ?,
                ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::uuid,
                ?, ?::uuid, ?, ?::uuid, ?, ?, ?, ?, ?,
                ?, ?::uuid, ?, ?::uuid
            )
            on conflict (subscription_instance_id, cycle_number) do nothing
            """;

        return cluboneJdbcTemplate.batchUpdate(sql, new BatchPreparedStatementSetter() {
            @Override
            public void setValues(PreparedStatement ps, int i) throws SQLException {
                SubscriptionBillingScheduleRow r = rows.get(i);

                ps.setObject(1, UUID.randomUUID());
                ps.setObject(2, r.getClientAgreementId());
                ps.setObject(3, r.getSubscriptionPlanId());
                ps.setObject(4, r.getSubscriptionInstanceId());
                ps.setInt(5, r.getCycleNumber());

                ps.setObject(6, r.getBillingPeriodStart());
                ps.setObject(7, r.getBillingPeriodEnd());
                ps.setObject(8, r.getBillingDate());

                ps.setBigDecimal(9, nz(r.getBaseAmount()));
                setNullableBigDecimal(ps, 10, r.getOverrideAmount());
                ps.setBigDecimal(11, nz(r.getSystemAdjustmentAmount()));
                ps.setBigDecimal(12, nz(r.getManualAdjustmentAmount()));
                ps.setBigDecimal(13, nz(r.getDiscountAmount()));
                ps.setBigDecimal(14, nz(r.getTaxAmount()));

                ps.setObject(15, r.getBillingScheduleStatusId());

                ps.setString(16, r.getPriceSourceType());
                ps.setObject(17, r.getPriceSourceId());

                ps.setString(18, r.getSourceEventType());
                ps.setObject(19, r.getSourceEventId());

                ps.setBoolean(20, Boolean.TRUE.equals(r.getIsFreezeCycle()));
                ps.setBoolean(21, Boolean.TRUE.equals(r.getIsCancellationCycle()));
                ps.setBoolean(22, Boolean.TRUE.equals(r.getIsProrated()));
                ps.setBoolean(23, !Boolean.FALSE.equals(r.getIsGenerated()));
                ps.setBoolean(24, Boolean.TRUE.equals(r.getIsLocked()));

                ps.setString(25, r.getNotes());

                ps.setObject(26, r.getInvoiceId());
                if (r.getBilledOn() != null) {
                    ps.setObject(27, r.getBilledOn());
                } else {
                    ps.setNull(27, Types.TIMESTAMP_WITH_TIMEZONE);
                }

                ps.setObject(28, r.getCreatedBy());
            }

            @Override
            public int getBatchSize() {
                return rows.size();
            }
        });
    }

    @Override
    public boolean existsByInstanceAndCycle(UUID subscriptionInstanceId, Integer cycleNumber) {
        String sql = """
            select exists(
                select 1
                from client_subscription_billing.subscription_billing_schedule
                where subscription_instance_id = ?::uuid
                  and cycle_number = ?
            )
            """;

        Boolean exists = cluboneJdbcTemplate.queryForObject(
                sql,
                Boolean.class,
                subscriptionInstanceId,
                cycleNumber
        );
        return Boolean.TRUE.equals(exists);
    }

    @Override
    public Integer countFutureRows(UUID subscriptionInstanceId, LocalDate fromDate) {
        String sql = """
            select count(1)
            from client_subscription_billing.subscription_billing_schedule
            where subscription_instance_id = ?::uuid
              and billing_date >= ?
              and invoice_id is null
            """;

        Integer count = cluboneJdbcTemplate.queryForObject(
                sql,
                Integer.class,
                subscriptionInstanceId,
                fromDate
        );
        return count == null ? 0 : count;
    }

    @Override
    public List<CyclePriceProjection> findCyclePrices(UUID subscriptionPlanId) {
        String sql = """
            select
                subscription_plan_cycle_price_id,
                price_cycle_band_id,
                cycle_start,
                cycle_end,
                effective_unit_price
            from client_subscription_billing.subscription_plan_cycle_price
            where subscription_plan_id = ?::uuid
            order by cycle_start asc
            """;

        return cluboneJdbcTemplate.query(sql, (rs, rowNum) -> {
            CyclePriceProjection p = new CyclePriceProjection();
            p.setSubscriptionPlanCyclePriceId(UUID.fromString(rs.getString("subscription_plan_cycle_price_id")));
            String bandId = rs.getString("price_cycle_band_id");
            p.setPriceCycleBandId(bandId == null ? null : UUID.fromString(bandId));
            p.setCycleStart(rs.getInt("cycle_start"));
            int cycleEndVal = rs.getInt("cycle_end");
            p.setCycleEnd(rs.wasNull() ? null : cycleEndVal);
            p.setEffectiveUnitPrice(rs.getBigDecimal("effective_unit_price"));
            return p;
        }, subscriptionPlanId);
    }

    private BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    private void setNullableBigDecimal(PreparedStatement ps, int index, BigDecimal value) throws SQLException {
        if (value == null) {
            ps.setNull(index, Types.NUMERIC);
        } else {
            ps.setBigDecimal(index, value);
        }
    }
}