package io.clubone.transaction.helper;

import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.constraints.NotNull;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.http.*;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.*;
import java.util.*;
import java.util.stream.Collectors;

@Component
public class ClientAgreementOrchestrator {

    private static final UUID DEFAULT_ACTOR_ID =
            UUID.fromString("1934776b-1912-4886-9890-023f21f6ba3b");
    private static final UUID DEFAULT_LOCATION_ID =
            UUID.fromString("290ea7fa-7842-44ba-bf09-578c6e8a7842");

    private final NamedParameterJdbcTemplate namedJdbc;
    private final RestTemplate restTemplate;

    // e.g. http://client-agreement-service:8080
    @Value("${client.agreement.service.base-url}")
    private String clientAgreementServiceBaseUrl;

    public ClientAgreementOrchestrator(NamedParameterJdbcTemplate namedJdbc,
                                       RestTemplate restTemplate) {
        this.namedJdbc = namedJdbc;
        this.restTemplate = restTemplate;
    }

    /**
     * Main helper: builds ClientAgreementCreateRequest from upstream payload,
     * then calls the downstream Client Agreement service.
     *
     * @return created client_agreement_id
     */
    public UUID createClientAgreementFromPurchase(PurchaseRequest purchase) {
        if (purchase == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "PurchaseRequest cannot be null");
        }

        if (purchase.entities == null || purchase.entities.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "PurchaseRequest.entities cannot be empty");
        }

        // For now take the first entity as the "primary" agreement entity.
        PurchaseEntity primaryEntity = purchase.entities.get(0);
        if (primaryEntity.entityId == null || primaryEntity.entityId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Primary entityId (agreementId) is required");
        }

        UUID agreementId = UUID.fromString(primaryEntity.entityId);
        UUID clientRoleId = UUID.fromString(purchase.clientRoleId);
        UUID levelRefId = UUID.fromString(purchase.levelId);

        // Use primary entity startDate as "as of" for version/location resolution
        LocalDate startDate = (primaryEntity.startDate != null)
                ? primaryEntity.startDate
                : LocalDate.now(ZoneOffset.UTC);

        OffsetDateTime startAsOfUtc = startDate
                .atStartOfDay(ZoneOffset.UTC)
                .toOffsetDateTime();

        AgreementMeta meta = resolveAgreementMeta(agreementId, levelRefId, startAsOfUtc);

        // Build downstream create request
        ClientAgreementCreateRequest caReq = new ClientAgreementCreateRequest();
        caReq.agreementId = meta.agreementId;
        caReq.agreementVersionId = meta.agreementVersionId;
        caReq.agreementLocationId = meta.agreementLocationId;
        caReq.agreementClassificationId = meta.agreementClassificationId;
        caReq.clientRoleId = clientRoleId;

        // purchased_level_id from the resolved level_id (based on reference_entity_id)
        caReq.purchasedLevelId = meta.purchasedLevelId;

        // Purchased + Start dates
        caReq.purchasedOnUtc = OffsetDateTime.now(ZoneOffset.UTC);
        caReq.startDateUtc = startAsOfUtc;

        // Status: let downstream default to ACTIVE (code), but we can choose to set explicitly
        caReq.clientAgreementStatusCode = "ACTIVE";

        // createdBy from upstream (if present)
        if (purchase.createdBy != null && !purchase.createdBy.isBlank()) {
            // we will rely on header X-Actor-Id for audit; this is just FYI, could be leadSource, etc.
            // no direct mapping field in create request – you can extend if needed
        }

        // Promotions / upsell mapping (optional for now)
        caReq.promotions = List.of();   // you can map discounts here later
        caReq.upsellItems = List.of();  // map upsellItem=true lines here later

        // Call downstream service
        return callClientAgreementCreate(caReq);
    }

    /**
     * Resolves agreement_version_id, agreement_location_id, agreement_classification_id,
     * and purchased_level_id from agreement + level reference id.
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

    private UUID callClientAgreementCreate(ClientAgreementCreateRequest req) {
        String url = clientAgreementServiceBaseUrl + "/client-agreements/api/client-agreements";

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Actor-Id", DEFAULT_ACTOR_ID.toString());
        headers.set("X-Location-Id", DEFAULT_LOCATION_ID.toString());

        HttpEntity<ClientAgreementCreateRequest> entity =
                new HttpEntity<>(req, headers);

        try {
            ResponseEntity<ClientAgreementCreateResponse> resp =
                    restTemplate.postForEntity(url, entity, ClientAgreementCreateResponse.class);

            if (!resp.getStatusCode().is2xxSuccessful() ||
                    resp.getBody() == null ||
                    resp.getBody().clientAgreementId == null) {
                throw new ResponseStatusException(
                        HttpStatus.BAD_GATEWAY,
                        "Downstream client-agreement service returned " + resp.getStatusCode()
                );
            }

            return resp.getBody().clientAgreementId;

        } catch (HttpStatusCodeException ex) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_GATEWAY,
                    "Error calling client-agreement service: " + ex.getStatusCode() +
                            " body=" + ex.getResponseBodyAsString(),
                    ex
            );
        }
    }

    /* ───────────────────────────── Internal Models ───────────────────────────── */

    private static class AgreementMeta {
        UUID agreementId;
        UUID agreementClassificationId;
        UUID agreementVersionId;
        UUID agreementLocationId;
        UUID purchasedLevelId;
    }

    private static class AgreementMetaRowMapper implements RowMapper<AgreementMeta> {
        @Override
        public AgreementMeta mapRow(ResultSet rs, int rowNum) throws SQLException {
            AgreementMeta m = new AgreementMeta();
            m.agreementId = getUuid(rs, "agreement_id");
            m.agreementClassificationId = getUuid(rs, "agreement_classification_id");
            m.agreementVersionId = getUuid(rs, "agreement_version_id");
            m.agreementLocationId = getUuid(rs, "agreement_location_id");
            m.purchasedLevelId = getUuid(rs, "purchased_level_id");
            return m;
        }
    }

    private static UUID getUuid(ResultSet rs, String col) throws SQLException {
        Object o = rs.getObject(col);
        if (o == null) return null;
        if (o instanceof UUID u) return u;
        return UUID.fromString(o.toString());
    }

    /* ───────────── DTOs matching your upstream JSON ───────────── */

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class PurchaseRequest {
        @NotNull
        public String clientRoleId;
        @NotNull
        public String levelId;      // reference_entity_id in locations.levels
        public String createdBy;
        public List<PurchaseEntity> entities;
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class PurchaseEntity {
        public String entityTypeId;  // currently unused
        public String entityId;      // parent: agreementId
        public Integer quantity;
        public List<String> discountIds;
        public String promotionId;
        public LocalDate startDate;
        public List<PurchaseItem> items;
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class PurchaseItem {
        public String entityId;
        public Integer quantity;
        public BigDecimal price;
        public String pricePlanTemplateId;
        public Boolean upsellItem;
        public List<PriceBand> priceBands;
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class PriceBand {
        public String priceCycleBandId;
        public BigDecimal unitPrice;
        public Boolean isPriceOverridden;
    }

    /* ───────────── DTOs for downstream client-agreement create ───────────── */

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class ClientAgreementCreateRequest {

        public UUID agreementId;
        public UUID agreementVersionId;
        public UUID agreementLocationId;
        public UUID agreementClassificationId;
        public UUID clientRoleId;
        public UUID purchasedLevelId;

        public OffsetDateTime purchasedOnUtc;
        public LocalDateTime purchasedOnLocal;
        public String purchasedOnLocalTz;

        public OffsetDateTime startDateUtc;
        public LocalDateTime startDateLocal;
        public String startDateLocalTz;

        public OffsetDateTime endDateUtc;
        public LocalDateTime endDateLocal;
        public String endDateLocalTz;

        public OffsetDateTime obligationStartUtc;
        public OffsetDateTime obligationEndUtc;

        public UUID clientAgreementStatusId;
        public String clientAgreementStatusCode;

        public UUID leadSourceId;
        public UUID salesAdvisorId;

        public Boolean isSigned;
        public OffsetDateTime signedOnUtc;

        public List<PromotionCreateDto> promotions;
        public List<UpsellItemCreateDto> upsellItems;
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class PromotionCreateDto {
        public UUID promotionVersionId;
        public BigDecimal discountAmount;
        public String notes;
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class UpsellItemCreateDto {
        public Integer quantity;
        public BigDecimal unitPrice;
        public BigDecimal discountAmount;
        public BigDecimal taxAmount;
        public BigDecimal totalAmount;
        public String currencyCode;
        public UUID invoiceId;
        public UUID invoiceEntityId;
        public UUID itemVersionId;
    }

    public static class ClientAgreementCreateResponse {
        public UUID clientAgreementId;
        public OffsetDateTime createdOnUtc;
    }
}

