package io.clubone.transaction.subscription.billing.dao.impl;

import io.clubone.transaction.subscription.billing.dao.SubscriptionBillingScheduleManageDAO;
import io.clubone.transaction.subscription.billing.dto.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.List;
import java.util.UUID;

@Repository
public class SubscriptionBillingScheduleManageDAOImpl implements SubscriptionBillingScheduleManageDAO {

    private final JdbcTemplate cluboneJdbcTemplate;

    public SubscriptionBillingScheduleManageDAOImpl(JdbcTemplate cluboneJdbcTemplate) {
        this.cluboneJdbcTemplate = cluboneJdbcTemplate;
    }

    @Override
    public List<SubscriptionBillingScheduleItemDTO> getScheduleByClientAgreementId(UUID clientAgreementId) {
        String sql = """
            select
                s.billing_schedule_id,
                s.client_agreement_id,
                s.subscription_plan_id,
                s.subscription_instance_id,
                s.cycle_number,
                s.label,
                s.period_label,
                s.billing_period_start,
                s.billing_period_end,
                s.billing_date,
                s.quantity,
                s.unit_price,
                s.unit_price_before_discount,
                s.base_amount,
                s.override_amount,
                s.discount_amount,
                s.tax_amount,
                s.tax_pct,
                s.subtotal_before_tax,
                s.final_amount,
                s.is_prorated,
                s.is_one_time,
                s.is_final_cycle,
                s.billing_schedule_status_id,
                st.status_code,
                st.display_name as status_display_name,
                coalesce(adj.total_adjustment_amount, 0) as total_adjustment_amount
            from client_subscription_billing.subscription_billing_schedule s
            left join billing_config.billing_schedule_status st
                   on st.billing_schedule_status_id = s.billing_schedule_status_id
            left join (
                select
                    billing_schedule_id,
                    sum(amount) as total_adjustment_amount
                from client_subscription_billing.subscription_billing_schedule_adjustment
                where is_active = true
                group by billing_schedule_id
            ) adj on adj.billing_schedule_id = s.billing_schedule_id
            where s.client_agreement_id = ?::uuid
            order by s.billing_date asc, s.cycle_number asc
            """;

        System.out.println("===== getScheduleByClientAgreementId START =====");
        System.out.println("ClientAgreementId: " + clientAgreementId);
        System.out.println("Executing SQL for updated subscription_billing_schedule structure");

        List<SubscriptionBillingScheduleItemDTO> result = cluboneJdbcTemplate.query(sql, (rs, rowNum) -> {
            SubscriptionBillingScheduleItemDTO dto = new SubscriptionBillingScheduleItemDTO();

            dto.setBillingScheduleId(getUuid(rs, "billing_schedule_id"));
            dto.setClientAgreementId(getUuid(rs, "client_agreement_id"));
            dto.setSubscriptionPlanId(getUuid(rs, "subscription_plan_id"));
            dto.setSubscriptionInstanceId(getUuid(rs, "subscription_instance_id"));

            dto.setCycleNumber(rs.getInt("cycle_number"));
            dto.setBillingPeriodStart(rs.getDate("billing_period_start").toLocalDate());
            dto.setBillingPeriodEnd(rs.getDate("billing_period_end").toLocalDate());
            dto.setBillingDate(rs.getDate("billing_date").toLocalDate());

            dto.setBaseAmount(rs.getBigDecimal("base_amount"));
            dto.setOverrideAmount(rs.getBigDecimal("override_amount"));
            dto.setSystemAdjustmentAmount(rs.getBigDecimal("total_adjustment_amount"));
            dto.setManualAdjustmentAmount(BigDecimal.ZERO);

            dto.setDiscountAmount(rs.getBigDecimal("discount_amount"));
            dto.setTaxAmount(rs.getBigDecimal("tax_amount"));
            dto.setFinalAmount(rs.getBigDecimal("final_amount"));

            dto.setStatusCode(rs.getString("status_code"));
            dto.setStatusDisplayName(rs.getString("status_display_name"));

            dto.setIsFreezeCycle(false);
            dto.setIsCancellationCycle(false);
            dto.setIsProrated(rs.getObject("is_prorated", Boolean.class));
            dto.setIsGenerated(true);
            dto.setIsLocked(false);

            dto.setInvoiceId(null);
            dto.setNotes(buildNotes(rs));

            System.out.println("Mapped row #" + rowNum
                    + " | cycle=" + dto.getCycleNumber()
                    + " | billingDate=" + dto.getBillingDate()
                    + " | baseAmount=" + dto.getBaseAmount()
                    + " | finalAmount=" + dto.getFinalAmount()
                    + " | adjustmentAmount=" + dto.getSystemAdjustmentAmount());

            return dto;
        }, clientAgreementId);

        System.out.println("Total rows fetched: " + result.size());
        System.out.println("===== getScheduleByClientAgreementId END =====");

        return result;
    }
    private UUID getUuid(ResultSet rs, String column) throws SQLException {
        String value = rs.getString(column);
        return value == null ? null : UUID.fromString(value);
    }

    private String buildNotes(ResultSet rs) throws SQLException {
        String label = rs.getString("label");
        String periodLabel = rs.getString("period_label");
        Integer quantity = (Integer) rs.getObject("quantity");
        BigDecimal unitPrice = rs.getBigDecimal("unit_price");

        return "label=" + label
                + ", periodLabel=" + periodLabel
                + ", quantity=" + quantity
                + ", unitPrice=" + unitPrice;
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
    public UUID billingAdjustmentTypeId(String adjustmentTypeCode) {
        String sql = """
            select billing_adjustment_type_id
            from client_subscription_billing.lu_billing_adjustment_type
            where adjustment_type_code = ?
              and is_active = true
            limit 1
            """;
        return cluboneJdbcTemplate.queryForObject(
                sql,
                (rs, rowNum) -> UUID.fromString(rs.getString("billing_adjustment_type_id")),
                adjustmentTypeCode
        );
    }

    @Override
    public boolean isEditableScheduleRow(UUID billingScheduleId) {
        String sql = """
            select exists(
                select 1
                from client_subscription_billing.subscription_billing_schedule s
                where s.billing_schedule_id = ?::uuid
                  and s.is_locked = false
                  and s.invoice_id is null
            )
            """;
        Boolean val = cluboneJdbcTemplate.queryForObject(sql, Boolean.class, billingScheduleId);
        return Boolean.TRUE.equals(val);
    }

    @Override
    public int updateScheduleRow(UUID billingScheduleId, UUID statusId, UpdateBillingScheduleRequest request, UUID modifiedBy) {
        String sql = """
            update client_subscription_billing.subscription_billing_schedule
            set override_amount = ?,
                billing_schedule_status_id = ?::uuid,
                is_freeze_cycle = ?,
                is_cancellation_cycle = ?,
                is_prorated = ?,
                notes = ?,
                modified_on = now(),
                modified_by = ?::uuid
            where billing_schedule_id = ?::uuid
              and is_locked = false
              and invoice_id is null
            """;

        return cluboneJdbcTemplate.update(con -> {
            var ps = con.prepareStatement(sql);

            if (request.getOverrideAmount() != null) {
                ps.setBigDecimal(1, request.getOverrideAmount());
            } else {
                ps.setNull(1, Types.NUMERIC);
            }

            ps.setObject(2, statusId);
            ps.setBoolean(3, Boolean.TRUE.equals(request.getIsFreezeCycle()));
            ps.setBoolean(4, Boolean.TRUE.equals(request.getIsCancellationCycle()));
            ps.setBoolean(5, Boolean.TRUE.equals(request.getIsProrated()));
            ps.setString(6, request.getNotes());
            ps.setObject(7, modifiedBy);
            ps.setObject(8, billingScheduleId);
            return ps;
        });
    }

    @Override
    public int bulkUpdateScheduleRows(BulkUpdateBillingScheduleRequest request, UUID statusId, UUID modifiedBy) {
        StringBuilder sql = new StringBuilder("""
            update client_subscription_billing.subscription_billing_schedule
            set override_amount = ?,
                billing_schedule_status_id = ?::uuid,
                is_freeze_cycle = ?,
                is_cancellation_cycle = ?,
                is_prorated = ?,
                notes = ?,
                modified_on = now(),
                modified_by = ?::uuid
            where client_agreement_id = ?::uuid
              and is_locked = false
              and invoice_id is null
            """);

        if (request.getFromBillingDate() != null) {
            sql.append(" and billing_date >= ? ");
        }
        if (request.getToBillingDate() != null) {
            sql.append(" and billing_date <= ? ");
        }
        if (request.getFromCycleNumber() != null) {
            sql.append(" and cycle_number >= ? ");
        }
        if (request.getToCycleNumber() != null) {
            sql.append(" and cycle_number <= ? ");
        }

        return cluboneJdbcTemplate.update(con -> {
            var ps = con.prepareStatement(sql.toString());
            int idx = 1;

            if (request.getOverrideAmount() != null) {
                ps.setBigDecimal(idx++, request.getOverrideAmount());
            } else {
                ps.setNull(idx++, Types.NUMERIC);
            }

            ps.setObject(idx++, statusId);
            ps.setBoolean(idx++, Boolean.TRUE.equals(request.getIsFreezeCycle()));
            ps.setBoolean(idx++, Boolean.TRUE.equals(request.getIsCancellationCycle()));
            ps.setBoolean(idx++, Boolean.TRUE.equals(request.getIsProrated()));
            ps.setString(idx++, request.getNotes());
            ps.setObject(idx++, modifiedBy);
            ps.setObject(idx++, request.getClientAgreementId());

            if (request.getFromBillingDate() != null) {
                ps.setObject(idx++, request.getFromBillingDate());
            }
            if (request.getToBillingDate() != null) {
                ps.setObject(idx++, request.getToBillingDate());
            }
            if (request.getFromCycleNumber() != null) {
                ps.setInt(idx++, request.getFromCycleNumber());
            }
            if (request.getToCycleNumber() != null) {
                ps.setInt(idx++, request.getToCycleNumber());
            }

            return ps;
        });
    }

    @Override
    public int insertAdjustment(UUID billingScheduleId, UUID adjustmentTypeId, AddBillingScheduleAdjustmentRequest request, UUID createdBy) {
        String sql = """
            insert into client_subscription_billing.subscription_billing_schedule_adjustment (
                billing_schedule_adjustment_id,
                billing_schedule_id,
                billing_adjustment_type_id,
                amount,
                is_system_generated,
                reference_entity_type,
                reference_entity_id,
                notes,
                created_on,
                created_by
            ) values (
                gen_random_uuid(),
                ?::uuid,
                ?::uuid,
                ?,
                false,
                ?,
                ?::uuid,
                ?,
                now(),
                ?::uuid
            )
            """;

        return cluboneJdbcTemplate.update(sql,
                billingScheduleId,
                adjustmentTypeId,
                request.getAmount(),
                request.getReferenceEntityType(),
                request.getReferenceEntityId(),
                request.getReason(),
                createdBy
        );
    }

    
    
    @Override
    public List<BillingScheduleAdjustmentItemDTO> getAdjustmentsByBillingScheduleId(UUID billingScheduleId) {
        String sql = """
            select
                a.billing_schedule_adjustment_id,
                a.billing_schedule_id,
                t.adjustment_type_code,
                t.display_name as adjustment_type_display_name,
                a.amount,
                a.is_system_generated,
                a.reference_entity_type,
                a.reference_entity_id,
                a.notes,
                a.is_active,
                a.created_on,
                a.created_by,
                a.modified_on,
                a.modified_by
            from client_subscription_billing.subscription_billing_schedule_adjustment a
            join client_subscription_billing.lu_billing_adjustment_type t
              on t.billing_adjustment_type_id = a.billing_adjustment_type_id
            where a.billing_schedule_id = ?::uuid
            order by a.created_on asc
            """;

        return cluboneJdbcTemplate.query(sql, (rs, rowNum) -> {
            BillingScheduleAdjustmentItemDTO dto = new BillingScheduleAdjustmentItemDTO();
            dto.setBillingScheduleAdjustmentId(UUID.fromString(rs.getString("billing_schedule_adjustment_id")));
            dto.setBillingScheduleId(UUID.fromString(rs.getString("billing_schedule_id")));
            dto.setAdjustmentTypeCode(rs.getString("adjustment_type_code"));
            dto.setAdjustmentTypeDisplayName(rs.getString("adjustment_type_display_name"));
            dto.setAmount(rs.getBigDecimal("amount"));
            dto.setIsSystemGenerated(rs.getBoolean("is_system_generated"));
            dto.setReferenceEntityType(rs.getString("reference_entity_type"));

            String refId = rs.getString("reference_entity_id");
            dto.setReferenceEntityId(refId == null ? null : UUID.fromString(refId));

            dto.setNotes(rs.getString("notes"));
            dto.setIsActive(rs.getBoolean("is_active"));

            var createdTs = rs.getObject("created_on", java.time.OffsetDateTime.class);
            dto.setCreatedOn(createdTs);

            String createdBy = rs.getString("created_by");
            dto.setCreatedBy(createdBy == null ? null : UUID.fromString(createdBy));

            var modifiedTs = rs.getObject("modified_on", java.time.OffsetDateTime.class);
            dto.setModifiedOn(modifiedTs);

            String modifiedBy = rs.getString("modified_by");
            dto.setModifiedBy(modifiedBy == null ? null : UUID.fromString(modifiedBy));

            return dto;
        }, billingScheduleId);
    }

    @Override
    public UUID findBillingScheduleIdByAdjustmentId(UUID billingScheduleAdjustmentId) {
        String sql = """
            select billing_schedule_id
            from client_subscription_billing.subscription_billing_schedule_adjustment
            where billing_schedule_adjustment_id = ?::uuid
            limit 1
            """;

        return cluboneJdbcTemplate.queryForObject(
                sql,
                (rs, rowNum) -> UUID.fromString(rs.getString("billing_schedule_id")),
                billingScheduleAdjustmentId
        );
    }

    @Override
    public int updateAdjustment(UUID billingScheduleAdjustmentId,
                                UpdateBillingScheduleAdjustmentRequest request,
                                UUID modifiedBy) {
        String sql = """
            update client_subscription_billing.subscription_billing_schedule_adjustment
            set amount = ?,
                reference_entity_type = ?,
                reference_entity_id = ?::uuid,
                notes = ?,
                modified_on = now(),
                modified_by = ?::uuid
            where billing_schedule_adjustment_id = ?::uuid
              and is_active = true
            """;

        return cluboneJdbcTemplate.update(sql,
                request.getAmount(),
                request.getReferenceEntityType(),
                request.getReferenceEntityId(),
                request.getReason(),
                modifiedBy,
                billingScheduleAdjustmentId
        );
    }

    @Override
    public int deactivateAdjustment(UUID billingScheduleAdjustmentId, UUID modifiedBy) {
        String sql = """
            update client_subscription_billing.subscription_billing_schedule_adjustment
            set is_active = false,
                modified_on = now(),
                modified_by = ?::uuid
            where billing_schedule_adjustment_id = ?::uuid
              and is_active = true
            """;

        return cluboneJdbcTemplate.update(sql, modifiedBy, billingScheduleAdjustmentId);
    }

    @Override
    public int recomputeManualAdjustmentAmount(UUID billingScheduleId, UUID modifiedBy) {
        String sql = """
            update client_subscription_billing.subscription_billing_schedule s
            set manual_adjustment_amount = coalesce((
                    select sum(a.amount)
                    from client_subscription_billing.subscription_billing_schedule_adjustment a
                    where a.billing_schedule_id = s.billing_schedule_id
                      and a.is_active = true
                ), 0),
                modified_on = now(),
                modified_by = ?::uuid
            where s.billing_schedule_id = ?::uuid
              and s.is_locked = false
              and s.invoice_id is null
            """;

        return cluboneJdbcTemplate.update(sql, modifiedBy, billingScheduleId);
    }
    
    @Override
    public int deleteFutureGeneratedRows(UUID clientAgreementId,
                                         java.time.LocalDate fromBillingDate,
                                         boolean preserveManualOverrides) {
        StringBuilder sql = new StringBuilder("""
            delete from client_subscription_billing.subscription_billing_schedule
            where client_agreement_id = ?::uuid
              and billing_date >= ?
              and invoice_id is null
              and is_locked = false
              and is_generated = true
            """);

        if (preserveManualOverrides) {
            sql.append("""
                and coalesce(override_amount, base_amount) = base_amount
                and coalesce(manual_adjustment_amount, 0) = 0
            """);
        }

        return cluboneJdbcTemplate.update(sql.toString(), clientAgreementId, fromBillingDate);
    }

    @Override
    public List<SubscriptionBillingScheduleItemDTO> getEditableFutureRows(UUID clientAgreementId,
                                                                          java.time.LocalDate fromBillingDate) {
        String sql = """
            select
                s.billing_schedule_id,
                s.client_agreement_id,
                s.subscription_plan_id,
                s.subscription_instance_id,
                s.cycle_number,
                s.billing_period_start,
                s.billing_period_end,
                s.billing_date,
                s.base_amount,
                s.override_amount,
                s.system_adjustment_amount,
                s.manual_adjustment_amount,
                s.discount_amount,
                s.tax_amount,
                s.final_amount,
                st.status_code,
                st.display_name as status_display_name,
                s.is_freeze_cycle,
                s.is_cancellation_cycle,
                s.is_prorated,
                s.is_generated,
                s.is_locked,
                s.invoice_id,
                s.notes
            from client_subscription_billing.subscription_billing_schedule s
            join client_subscription_billing.lu_billing_schedule_status st
              on st.billing_schedule_status_id = s.billing_schedule_status_id
            where s.client_agreement_id = ?::uuid
              and s.billing_date >= ?
              and s.invoice_id is null
              and s.is_locked = false
            order by s.billing_date asc, s.cycle_number asc
            """;

        return getScheduleByClientAgreementId(clientAgreementId).stream()
                .filter(x -> !x.getBillingDate().isBefore(fromBillingDate))
                .toList();
    }

    @Override
    public void insertAuditLog(String eventType,
                               String entityType,
                               UUID entityId,
                               String action,
                               UUID userId,
                               String userEmail,
                               String ipAddress,
                               String userAgent,
                               String detailsJson) {
        String sql = """
            insert into client_subscription_billing.billing_audit_log (
                audit_log_id,
                event_type,
                entity_type,
                entity_id,
                action,
                user_id,
                details,
                created_on,
                ip_address,
                user_agent,
                user_email
            ) values (
                gen_random_uuid(),
                ?,
                ?,
                ?::uuid,
                ?,
                ?,
                ?::jsonb,
                now(),
                ?,
                ?,
                ?
            )
            """;

        cluboneJdbcTemplate.update(sql,
                eventType,
                entityType,
                entityId,
                action,
                userId == null ? null : userId.toString(),
                detailsJson,
                ipAddress,
                userAgent,
                userEmail
        );
    }


}
