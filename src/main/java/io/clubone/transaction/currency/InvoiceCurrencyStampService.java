package io.clubone.transaction.currency;

import io.clubone.transaction.security.AccessContext;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/**
 * Resolves transactional currency for POS/invoice create and locks reporting FX projection
 * using the same {@code billing_config} tables as billing-job (APPROVED rates only).
 */
@Service
public class InvoiceCurrencyStampService {

    private final JdbcTemplate jdbc;

    public InvoiceCurrencyStampService(@Qualifier("cluboneJdbcTemplate") JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record Stamp(
            String currencyCode,
            BigDecimal amountReporting,
            UUID fxRateId,
            Instant fxAsOf) {}

    /**
     * Prefer request currency; else location currency via invoice level.
     * Fail-closed if neither can be resolved (avoids silent INR default).
     */
    public String requireCurrencyCode(String requestCurrencyCode, UUID levelId, UUID clientRoleId) {
        Optional<String> fromRequest = normalize(requestCurrencyCode);
        if (fromRequest.isPresent()) {
            return fromRequest.get();
        }
        Optional<String> fromLevel = resolveFromLevel(levelId);
        if (fromLevel.isPresent()) {
            return fromLevel.get();
        }
        Optional<String> fromClient = resolveFromClientRole(clientRoleId);
        if (fromClient.isPresent()) {
            return fromClient.get();
        }
        throw new IllegalStateException(
                "Invoice currency_code is required: set currencyCode on the request or configure location currency");
    }

    /**
     * Builds reporting stamp. Same currency as reporting → 1:1. Missing APPROVED FX → reporting null
     * (does not block POS charge).
     */
    public Stamp stamp(BigDecimal totalAmount, String currencyCode) {
        String ccy = normalize(currencyCode)
                .orElseThrow(() -> new IllegalStateException("currencyCode required for stamp"));
        Optional<String> reportingOpt = resolveReportingCurrencyCode();
        if (reportingOpt.isEmpty()) {
            // Reporting currency unset — charge still stamps transactional currency only.
            return new Stamp(ccy, null, null, null);
        }
        String reporting = reportingOpt.get();
        Instant asOf = Instant.now();
        if (ccy.equalsIgnoreCase(reporting)) {
            BigDecimal amount = totalAmount == null
                    ? BigDecimal.ZERO.setScale(4, RoundingMode.HALF_UP)
                    : totalAmount.setScale(4, RoundingMode.HALF_UP);
            return new Stamp(ccy, amount, null, asOf);
        }
        Optional<FxRow> rate = findApprovedRate(ccy, reporting, asOf);
        if (rate.isEmpty() || totalAmount == null) {
            return new Stamp(ccy, null, null, null);
        }
        BigDecimal converted = totalAmount.multiply(rate.get().rate()).setScale(4, RoundingMode.HALF_UP);
        Instant fxAsOf = rate.get().asOf() != null ? rate.get().asOf() : asOf;
        return new Stamp(ccy, converted, rate.get().fxRateId(), fxAsOf);
    }

    private Optional<String> resolveFromLevel(UUID levelId) {
        if (levelId == null) {
            return Optional.empty();
        }
        try {
            String code = jdbc.query(
                    """
                    SELECT upper(trim(c.currency_code)) AS currency_code
                    FROM locations.levels lv
                    JOIN locations.location loc ON loc.location_id = lv.reference_entity_id
                    JOIN locations.lu_currency c ON c.currency_id = loc.currency_id
                    WHERE lv.level_id = ?::uuid
                    LIMIT 1
                    """,
                    rs -> rs.next() ? rs.getString("currency_code") : null,
                    levelId.toString());
            return normalize(code);
        } catch (DataAccessException ex) {
            return Optional.empty();
        }
    }

    private Optional<String> resolveFromClientRole(UUID clientRoleId) {
        if (clientRoleId == null) {
            return Optional.empty();
        }
        try {
            String code = jdbc.query(
                    """
                    SELECT upper(trim(c.currency_code)) AS currency_code
                    FROM clients.client_role cr
                    JOIN locations.location loc ON loc.location_id = cr.location_id
                    JOIN locations.lu_currency c ON c.currency_id = loc.currency_id
                    WHERE cr.client_role_id = ?::uuid
                    LIMIT 1
                    """,
                    rs -> rs.next() ? rs.getString("currency_code") : null,
                    clientRoleId.toString());
            return normalize(code);
        } catch (DataAccessException ex) {
            return Optional.empty();
        }
    }

    private Optional<String> resolveReportingCurrencyCode() {
        try {
            String code = jdbc.query(
                    """
                    SELECT upper(trim(reporting_currency_code)) AS reporting_currency_code
                    FROM billing_config.billing_tenant_settings
                    WHERE application_id = ?::uuid
                    LIMIT 1
                    """,
                    rs -> rs.next() ? rs.getString("reporting_currency_code") : null,
                    AccessContext.applicationId().toString());
            return normalize(code);
        } catch (DataAccessException ex) {
            return Optional.empty();
        }
    }

    private Optional<FxRow> findApprovedRate(String from, String to, Instant asOf) {
        try {
            return Optional.ofNullable(jdbc.query(
                    """
                    SELECT fx_rate_id, rate, as_of
                    FROM billing_config.fx_rate
                    WHERE application_id = ?::uuid
                      AND from_currency = ?
                      AND to_currency = ?
                      AND is_active = true
                      AND approval_status = 'APPROVED'
                      AND as_of <= ?::timestamptz
                    ORDER BY as_of DESC
                    LIMIT 1
                    """,
                    rs -> {
                        if (!rs.next()) {
                            return null;
                        }
                        Timestamp ts = rs.getTimestamp("as_of");
                        return new FxRow(
                                (UUID) rs.getObject("fx_rate_id"),
                                rs.getBigDecimal("rate"),
                                ts != null ? ts.toInstant() : null);
                    },
                    AccessContext.applicationId().toString(),
                    from,
                    to,
                    Timestamp.from(asOf)));
        } catch (DataAccessException ex) {
            return Optional.empty();
        }
    }

    private static Optional<String> normalize(String code) {
        if (!StringUtils.hasText(code)) {
            return Optional.empty();
        }
        String c = code.trim().toUpperCase(Locale.ROOT);
        if (c.length() != 3) {
            return Optional.empty();
        }
        return Optional.of(c);
    }

    private record FxRow(UUID fxRateId, BigDecimal rate, Instant asOf) {}
}
