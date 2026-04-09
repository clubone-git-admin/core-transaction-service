package io.clubone.transaction.helper;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.clubone.transaction.v2.vo.InvoiceRequest;
import io.clubone.transaction.v2.vo.Entity;
import lombok.Data;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.http.*;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.*;
import java.util.*;

@Service
public class ClientAgreementCreationHelper {

    // Fixed default IDs as requested
    private static final UUID DEFAULT_ACTOR_ID =
            UUID.fromString("1934776b-1912-4886-9890-023f21f6ba3b");
    private static final UUID DEFAULT_LOCATION_ID =
            UUID.fromString("290ea7fa-7842-44ba-bf09-578c6e8a7842");

    private final NamedParameterJdbcTemplate namedJdbc;
    private final RestTemplate restTemplate;

    // e.g. http://client-agreement-service:8080
    @Value("${client.agreement.service.base-url}")
    private String clientAgreementServiceBaseUrl;

    public ClientAgreementCreationHelper(NamedParameterJdbcTemplate namedJdbc,
                                         RestTemplate restTemplate) {
        this.namedJdbc = namedJdbc;
        this.restTemplate = restTemplate;
    }

    /**
     * Build ClientAgreementCreateRequest from InvoiceRequest and
     * call downstream Client Agreement API.
     *
     * @return created client_agreement_id
     */
    public UUID createClientAgreementFromInvoice(InvoiceRequest invoice) {
        if (invoice == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "InvoiceRequest cannot be null");
        }
        if (invoice.getClientRoleId() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "InvoiceRequest.clientRoleId is required");
        }
        if (invoice.getLevelId() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "InvoiceRequest.levelId is required (reference_entity_id)");
        }
        if (invoice.getEntities() == null || invoice.getEntities().isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "InvoiceRequest.entities cannot be empty");
        }

        // For now, treat the FIRST entity as the "parent" agreement entity
        Entity primary = Optional.ofNullable(invoice)
        	    .map(InvoiceRequest::getEntities)
        	    .orElseThrow(() -> new IllegalStateException("Invoice or entities list is null"))
        	    .stream()
        	    .filter(Objects::nonNull)
        	    .filter(e -> "AGREEMENT".equalsIgnoreCase(
        	            Optional.ofNullable(e.getEntityType()).orElse("")
        	    ))
        	    .findFirst()
        	    .orElseThrow(() -> new IllegalStateException("No primary Agreement entity found in invoice"));if (primary.getEntityId() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Primary entity.entityId (agreementId) is required");
        }

        UUID agreementId = primary.getEntityId();
        UUID requestedAgreementVersionId = primary.getEntityVersionId();
        UUID clientRoleId = invoice.getClientRoleId();
        UUID levelRefOrId = invoice.getLevelId();  // accepts levels.level_id or levels.reference_entity_id

        // Use primary entity startDate as as-of date; fallback to today UTC
        LocalDate startDate = primary.getStartDate() != null
                ? primary.getStartDate()
                : LocalDate.now(ZoneOffset.UTC);

        // 1) Resolve agreement_version_id, agreement_location_id, classification, purchased_level_id
        AgreementMeta meta = resolveAgreementMeta(agreementId, requestedAgreementVersionId, levelRefOrId, startDate);
        ZoneId zoneId = resolveZoneId(meta.getLocationTimeZone());
        OffsetDateTime purchasedOnUtc = OffsetDateTime.now(ZoneOffset.UTC);
        LocalDateTime purchasedOnLocal = LocalDateTime.now(zoneId);
        String purchasedOnLocalTz = zoneId.getId();

        LocalDateTime startDateLocal = startDate.atStartOfDay();
        OffsetDateTime startDateUtc = startDateLocal.atZone(zoneId).toOffsetDateTime().withOffsetSameInstant(ZoneOffset.UTC);
        String startDateLocalTz = zoneId.getId();

        EndDateBlock endDateBlock = computeEndDateBlock(startDate, zoneId, meta);

        // 2) Build downstream ClientAgreementCreateRequest
        ClientAgreementCreateRequest caReq = new ClientAgreementCreateRequest();
        caReq.setAgreementId(meta.getAgreementId());
        caReq.setAgreementVersionId(
                requestedAgreementVersionId != null ? requestedAgreementVersionId : meta.getAgreementVersionId());
        caReq.setAgreementLocationId(meta.getAgreementLocationId());
        caReq.setAgreementClassificationId(meta.getAgreementClassificationId());
        caReq.setClientRoleId(clientRoleId);

        // purchased_level_id from resolved locations.levels.level_id
        caReq.setPurchasedLevelId(meta.getPurchasedLevelId());

        caReq.setPurchasedOnUtc(purchasedOnUtc);
        caReq.setPurchasedOnLocal(purchasedOnLocal);
        caReq.setPurchasedOnLocalTz(purchasedOnLocalTz);

        caReq.setStartDateUtc(startDateUtc);
        caReq.setStartDateLocal(startDateLocal);
        caReq.setStartDateLocalTz(startDateLocalTz);

        caReq.setEndDateUtc(endDateBlock.endDateUtc());
        caReq.setEndDateLocal(endDateBlock.endDateLocal());
        caReq.setEndDateLocalTz(endDateBlock.endDateLocalTz());

        caReq.setObligationStartUtc(startDateUtc);
        caReq.setObligationEndUtc(endDateBlock.obligationEndUtc());

        // Let downstream default to ACTIVE status via code
        caReq.setClientAgreementStatusCode("DRAFT");

        if (primary.getPromotionId() != null) {
            PromotionCreateDto promo = new PromotionCreateDto();
            promo.setPromotionVersionId(primary.getPromotionId());
            caReq.setPromotions(List.of(promo));
        } else {
            caReq.setPromotions(Collections.emptyList());
        }
        caReq.setUpsellItems(Collections.emptyList());

        // 3) Call downstream API
        return invokeClientAgreementCreate(caReq);
    }

    /**
     * Resolve agreement metadata based on:
     *  - agreement_id
     *  - level.reference_entity_id
     *  - as-of timestamp (start date)
     */
    private AgreementMeta resolveAgreementMeta(UUID agreementId,
                                               UUID requestedAgreementVersionId,
                                               UUID levelReferenceOrId,
                                               LocalDate asOf) {

        String sql = """
            WITH lvl AS (
                SELECT l.level_id
                FROM locations.levels l
                WHERE l.reference_entity_id = :levelRefOrId
                   OR l.level_id = :levelRefOrId
                LIMIT 1
            ),
            av_choice AS (
                SELECT av.*
                FROM agreements.agreement_version av
                JOIN agreements.agreement ag ON ag.agreement_id = av.agreement_id
                WHERE av.agreement_id = :agreementId
                  AND av.is_active = TRUE
                  AND (:requestedAgreementVersionId IS NULL OR av.agreement_version_id = :requestedAgreementVersionId)
                  AND CAST(av.valid_from AS date) <= CAST(:asOf AS date)
                  AND (av.valid_to IS NULL OR CAST(av.valid_to AS date) >= CAST(:asOf AS date))
                ORDER BY
                  (CASE WHEN av.agreement_version_id = :requestedAgreementVersionId THEN 1 ELSE 0 END) DESC,
                  (CASE WHEN ag.current_version_id = av.agreement_version_id THEN 1 ELSE 0 END) DESC,
                  av.valid_from DESC
                LIMIT 1
            ),
            al_choice AS (
                SELECT al.*
                FROM agreements.agreement_location al
                JOIN lvl ON al.level_id = lvl.level_id
                JOIN av_choice av ON av.agreement_version_id = al.agreement_version_id
                WHERE al.is_active = TRUE
                  AND CAST(al.start_date AS date) <= CAST(:asOf AS date)
                  AND (al.end_date IS NULL OR CAST(al.end_date AS date) >= CAST(:asOf AS date))
                ORDER BY al.start_date DESC
                LIMIT 1
            )
            SELECT
                a.agreement_id,
                a.agreement_classification_id,
                av_choice.agreement_version_id,
                al_choice.agreement_location_id,
                (SELECT level_id FROM lvl) AS purchased_level_id,
                loc.timezone AS location_timezone,
                at.duration_value AS term_duration_value,
                ut.code AS term_duration_unit_code
            FROM agreements.agreement a
            JOIN av_choice ON av_choice.agreement_id = a.agreement_id
            JOIN al_choice ON al_choice.agreement_version_id = av_choice.agreement_version_id
            JOIN lvl ON true
            LEFT JOIN locations."location" loc ON loc.location_id = (
                SELECT l.reference_entity_id
                FROM locations.levels l
                WHERE l.level_id = lvl.level_id
                LIMIT 1
            )
            LEFT JOIN agreements.agreement_term at ON at.agreement_term_id = a.agreement_term_id
            LEFT JOIN agreements.lu_duration_unit_type ut ON ut.duration_unit_type_id = at.duration_unit_type_id
            WHERE a.agreement_id = :agreementId
            """;

        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("agreementId", agreementId)
                .addValue("levelRefOrId", levelReferenceOrId)
                .addValue("requestedAgreementVersionId", requestedAgreementVersionId)
                .addValue("asOf", asOf);

        try {
            return namedJdbc.queryForObject(sql, params, new AgreementMetaRowMapper());
        } catch (EmptyResultDataAccessException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Unable to resolve agreement metadata for agreementId=" + agreementId +
                            ", levelRefOrId=" + levelReferenceOrId +
                            ", requestedAgreementVersionId=" + requestedAgreementVersionId +
                            ", asOf=" + asOf);
        }
    }

    private UUID invokeClientAgreementCreate(ClientAgreementCreateRequest caReq) {
        String url = clientAgreementServiceBaseUrl;
        System.out.println("[invokeClientAgreementCreate] requestBody=" + caReq);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Actor-Id", DEFAULT_ACTOR_ID.toString());
        headers.set("X-Location-Id", DEFAULT_LOCATION_ID.toString());

        HttpEntity<ClientAgreementCreateRequest> entity =
                new HttpEntity<>(caReq, headers);

        try {
            ResponseEntity<ClientAgreementCreateResponse> resp =
                    restTemplate.postForEntity(url, entity, ClientAgreementCreateResponse.class);

            if (!resp.getStatusCode().is2xxSuccessful() ||
                    resp.getBody() == null ||
                    resp.getBody().getClientAgreementId() == null) {
                throw new ResponseStatusException(
                        HttpStatus.BAD_GATEWAY,
                        "Client-agreement service returned " + resp.getStatusCode()
                );
            }

            return resp.getBody().getClientAgreementId();

        } catch (HttpStatusCodeException ex) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_GATEWAY,
                    "Error calling client-agreement service: " + ex.getStatusCode() +
                            " body=" + ex.getResponseBodyAsString(),
                    ex
            );
        }
    }

    private ZoneId resolveZoneId(String tz) {
        if (tz == null || tz.isBlank()) {
            return ZoneOffset.UTC;
        }
        try {
            return ZoneId.of(tz.trim());
        } catch (DateTimeException ex) {
            return ZoneOffset.UTC;
        }
    }

    /**
     * Contract end is the last inclusive instant before the exclusive end date.
     * Example: start 2026-04-09 00:00, one-year exclusive end 2027-04-09 → last inclusive
     * {@code 2027-04-08T23:59:59} in the location zone.
     */
    private EndDateBlock computeEndDateBlock(LocalDate startDate, ZoneId zoneId, AgreementMeta meta) {
        boolean isContinuous = meta.getTermDurationValue() == null
                || meta.getTermDurationUnitCode() == null
                || "CONTINUOUS".equalsIgnoreCase(meta.getTermDurationUnitCode())
                || "CONTINUE".equalsIgnoreCase(meta.getTermDurationUnitCode());

        if (isContinuous) {
            LocalDate exclusiveEnd = startDate.plusYears(1);
            TermEndInstant obligation = lastInclusiveEndBeforeExclusiveEnd(exclusiveEnd, zoneId);
            return new EndDateBlock(null, null, null, obligation.utc());
        }

        LocalDate exclusiveEnd = addTermDuration(startDate, meta.getTermDurationValue(), meta.getTermDurationUnitCode());
        if (exclusiveEnd == null) {
            return new EndDateBlock(null, null, null, null);
        }
        TermEndInstant end = lastInclusiveEndBeforeExclusiveEnd(exclusiveEnd, zoneId);
        return new EndDateBlock(end.utc(), end.local(), end.tzId(), end.utc());
    }

    private static TermEndInstant lastInclusiveEndBeforeExclusiveEnd(LocalDate exclusiveEndDate, ZoneId zoneId) {
        LocalDate lastInclusiveLocalDate = exclusiveEndDate.minusDays(1);
        LocalDateTime lastInclusiveLocal = lastInclusiveLocalDate.atTime(23, 59, 59);
        OffsetDateTime utc = lastInclusiveLocal.atZone(zoneId).toOffsetDateTime().withOffsetSameInstant(ZoneOffset.UTC);
        return new TermEndInstant(utc, lastInclusiveLocal, zoneId.getId());
    }

    private record TermEndInstant(OffsetDateTime utc, LocalDateTime local, String tzId) {
    }

    private LocalDate addTermDuration(LocalDate startDate, Integer duration, String unitCode) {
        if (startDate == null || duration == null || unitCode == null) {
            return null;
        }
        return switch (unitCode.toUpperCase(Locale.ROOT)) {
            case "DAY", "DAYS" -> startDate.plusDays(duration);
            case "WEEK", "WEEKS" -> startDate.plusWeeks(duration);
            case "MONTH", "MONTHS" -> startDate.plusMonths(duration);
            case "YEAR", "YEARS" -> startDate.plusYears(duration);
            default -> startDate.plusMonths(duration);
        };
    }

    /* ======================= Internal models / mappers ======================= */

    @Data
    private static class AgreementMeta {
        private UUID agreementId;
        private UUID agreementClassificationId;
        private UUID agreementVersionId;
        private UUID agreementLocationId;
        private UUID purchasedLevelId;
        private String locationTimeZone;
        private Integer termDurationValue;
        private String termDurationUnitCode;
    }

    private record EndDateBlock(OffsetDateTime endDateUtc,
                                LocalDateTime endDateLocal,
                                String endDateLocalTz,
                                OffsetDateTime obligationEndUtc) {
    }

    private static class AgreementMetaRowMapper implements RowMapper<AgreementMeta> {
        @Override
        public AgreementMeta mapRow(ResultSet rs, int rowNum) throws SQLException {
            AgreementMeta m = new AgreementMeta();
            m.setAgreementId(getUuid(rs, "agreement_id"));
            m.setAgreementClassificationId(getUuid(rs, "agreement_classification_id"));
            m.setAgreementVersionId(getUuid(rs, "agreement_version_id"));
            m.setAgreementLocationId(getUuid(rs, "agreement_location_id"));
            m.setPurchasedLevelId(getUuid(rs, "purchased_level_id"));
            m.setLocationTimeZone(rs.getString("location_timezone"));
            Object termDuration = rs.getObject("term_duration_value");
            m.setTermDurationValue(termDuration == null ? null : ((Number) termDuration).intValue());
            m.setTermDurationUnitCode(rs.getString("term_duration_unit_code"));
            return m;
        }
    }

    private static UUID getUuid(ResultSet rs, String col) throws SQLException {
        Object o = rs.getObject(col);
        if (o == null) return null;
        if (o instanceof UUID u) return u;
        return UUID.fromString(o.toString());
    }

    /* ================= DTOs for downstream client-agreement create ========== */

    @Data
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class ClientAgreementCreateRequest {
        private UUID agreementId;
        private UUID agreementVersionId;
        private UUID agreementLocationId;
        private UUID agreementClassificationId;
        private UUID clientRoleId;
        private UUID purchasedLevelId;

        private OffsetDateTime purchasedOnUtc;
        private LocalDateTime purchasedOnLocal;
        private String purchasedOnLocalTz;

        private OffsetDateTime startDateUtc;
        private LocalDateTime startDateLocal;
        private String startDateLocalTz;

        private OffsetDateTime endDateUtc;
        private LocalDateTime endDateLocal;
        private String endDateLocalTz;

        private OffsetDateTime obligationStartUtc;
        private OffsetDateTime obligationEndUtc;

        private UUID clientAgreementStatusId;
        private String clientAgreementStatusCode;

        private UUID leadSourceId;
        private UUID salesAdvisorId;

        private Boolean isSigned;
        private OffsetDateTime signedOnUtc;

        private List<PromotionCreateDto> promotions;
        private List<UpsellItemCreateDto> upsellItems;
    }

    @Data
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class PromotionCreateDto {
        private UUID promotionVersionId;
        private BigDecimal discountAmount;
        private String notes;
    }

    @Data
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class UpsellItemCreateDto {
        private Integer quantity;
        private BigDecimal unitPrice;
        private BigDecimal discountAmount;
        private BigDecimal taxAmount;
        private BigDecimal totalAmount;
        private String currencyCode;
        private UUID invoiceId;
        private UUID invoiceEntityId;
        private UUID itemVersionId;
    }

    @Data
    public static class ClientAgreementCreateResponse {
        private UUID clientAgreementId;
        private OffsetDateTime createdOnUtc;
    }
}

