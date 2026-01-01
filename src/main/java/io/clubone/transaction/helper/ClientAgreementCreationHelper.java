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
        UUID clientRoleId = invoice.getClientRoleId();
        UUID levelRefId = invoice.getLevelId();  // reference_entity_id in locations.levels

        // Use primary entity startDate as as-of date; fallback to today UTC
        LocalDate startDate = primary.getStartDate() != null
                ? primary.getStartDate()
                : LocalDate.now(ZoneOffset.UTC);

        OffsetDateTime startAsOfUtc = startDate
                .atStartOfDay()
                .atOffset(ZoneOffset.UTC);

        // 1) Resolve agreement_version_id, agreement_location_id, classification, purchased_level_id
        AgreementMeta meta = resolveAgreementMeta(agreementId, levelRefId, startAsOfUtc);

        // 2) Build downstream ClientAgreementCreateRequest
        ClientAgreementCreateRequest caReq = new ClientAgreementCreateRequest();
        caReq.setAgreementId(meta.getAgreementId());
        caReq.setAgreementVersionId(meta.getAgreementVersionId());
        caReq.setAgreementLocationId(meta.getAgreementLocationId());
        caReq.setAgreementClassificationId(meta.getAgreementClassificationId());
        caReq.setClientRoleId(clientRoleId);

        // purchased_level_id from resolved lvl.level_id
        caReq.setPurchasedLevelId(meta.getPurchasedLevelId());
        System.out.println("levelId "+meta.getPurchasedLevelId());

        caReq.setPurchasedOnUtc(OffsetDateTime.now(ZoneOffset.UTC));
        caReq.setStartDateUtc(startAsOfUtc);

        // Let downstream default to ACTIVE status via code
        caReq.setClientAgreementStatusCode("DRAFT");

        // (Optional) If you want, you can later map promotions/upsell items here
        caReq.setPromotions(Collections.emptyList());
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
                                               UUID levelReferenceId,
                                               OffsetDateTime asOf) {

        String sql = """
            WITH lvl AS (
                SELECT l.level_id
                FROM locations.levels l
                WHERE l.reference_entity_id = :levelRefId
            ),
            av_choice AS (
                SELECT av.*
                FROM agreements.agreement_version av
                WHERE av.agreement_id = :agreementId
                  AND av.is_active = TRUE
                  AND av.valid_from <= :asOf
                  AND (av.valid_to IS NULL OR av.valid_to >= :asOf)
                ORDER BY av.valid_from DESC
                LIMIT 1
            ),
            al_choice AS (
                SELECT al.*
                FROM agreements.agreement_location al
                JOIN lvl ON al.level_id = lvl.level_id
                JOIN av_choice av ON av.agreement_version_id = al.agreement_version_id
                WHERE al.is_active = TRUE
                  AND al.start_date <= :asOf
                  AND (al.end_date IS NULL OR al.end_date >= :asOf)
                ORDER BY al.start_date DESC
                LIMIT 1
            )
            SELECT
                a.agreement_id,
                a.agreement_classification_id,
                av_choice.agreement_version_id,
                al_choice.agreement_location_id,
                (SELECT level_id FROM lvl) AS purchased_level_id
            FROM agreements.agreement a
            JOIN av_choice ON av_choice.agreement_id = a.agreement_id
            JOIN al_choice ON al_choice.agreement_version_id = av_choice.agreement_version_id
            WHERE a.agreement_id = :agreementId
            """;

        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("agreementId", agreementId)
                .addValue("levelRefId", levelReferenceId)
                .addValue("asOf", asOf);

        try {
            return namedJdbc.queryForObject(sql, params, new AgreementMetaRowMapper());
        } catch (EmptyResultDataAccessException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Unable to resolve agreement metadata for agreementId=" + agreementId +
                            ", levelRefId=" + levelReferenceId +
                            ", asOf=" + asOf);
        }
    }

    private UUID invokeClientAgreementCreate(ClientAgreementCreateRequest caReq) {
        String url = clientAgreementServiceBaseUrl;

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

    /* ======================= Internal models / mappers ======================= */

    @Data
    private static class AgreementMeta {
        private UUID agreementId;
        private UUID agreementClassificationId;
        private UUID agreementVersionId;
        private UUID agreementLocationId;
        private UUID purchasedLevelId;
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

