package io.clubone.transaction.helper;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.http.*;
import org.springframework.jdbc.core.*;
import org.springframework.jdbc.core.namedparam.*;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import io.clubone.transaction.security.TenantContext;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@RestController
@RequestMapping("/client-agreements/api")
@Validated
public class ClientAgreementController {

    private static final Logger log = LoggerFactory.getLogger(ClientAgreementController.class);

    private static final String HDR_LOCATION_ID = "X-Location-Id";
    private static final String HDR_ACTOR_ID = "X-Actor-Id";

    private static final ConcurrentHashMap<String, UUID> STATUS_ID_BY_CODE = new ConcurrentHashMap<>();

    private final NamedParameterJdbcTemplate namedJdbc;
    private final JdbcTemplate jdbc;

    public ClientAgreementController(NamedParameterJdbcTemplate namedJdbc, JdbcTemplate jdbc) {
        this.namedJdbc = namedJdbc;
        this.jdbc = jdbc;
    }

    /* ─────────────────────── infra helpers ─────────────────────── */

    private static TenantContext requireCtx() {
        var ctx = TenantContext.get();
        if (ctx == null) {
            throw new IllegalStateException("TenantContext not initialized");
        }
        return ctx;
    }

    private static UUID mustUuidHeader(HttpHeaders headers, String name) {
        String raw = headers.getFirst(name);
        if (raw == null || raw.isBlank()) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Missing required header: " + name
            );
        }
        try {
            return UUID.fromString(raw.trim());
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Invalid UUID in header " + name + ": " + raw
            );
        }
    }

    private static UUID toUuidOrNull(String s) {
        if (s == null || s.isBlank()) return null;
        return UUID.fromString(s.trim());
    }

    private static OffsetDateTime nowUtc() {
        return OffsetDateTime.now(ZoneOffset.UTC);
    }

    private static <T> T required(T value, String field) {
        if (value == null) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Missing required field: " + field
            );
        }
        return value;
    }

    /* ─────────────────────── GET endpoint ───────────────────────
     * GET /client-agreements/api/client-agreements?clientRoleId=...&activeOnly=true
     * Headers: X-Location-Id, X-Actor-Id
     */

    @GetMapping("/client-agreements")
    public ResponseEntity<List<ClientAgreementResponse>> getClientAgreements(
            @RequestHeader HttpHeaders headers,
            @RequestParam("clientRoleId") String clientRoleIdStr,
            @RequestParam(value = "activeOnly", defaultValue = "true") boolean activeOnly
    ) {
        UUID clientRoleId = toUuidOrFail(clientRoleIdStr, "clientRoleId");
        UUID locationId = mustUuidHeader(headers, HDR_LOCATION_ID);
        // actorId header is enforced but not used directly here, still validate it
        mustUuidHeader(headers, HDR_ACTOR_ID);

        var ctx = requireCtx();
        UUID applicationId = ctx.applicationId(); // assumes getter

        // 1. Fetch base agreements for this client + location + application
        String sql = """
            WITH loc AS (
                SELECT l.level_id
                FROM locations.levels l
                WHERE l.reference_entity_id = :locationId
            )
            SELECT
                ca.client_agreement_id,
                ca.client_agreement_code,
                ca.agreement_id,
                a.name AS agreement_name,
                ca.agreement_version_id,
                av.version_number,
                ca.agreement_location_id,
                al.level_id AS agreement_level_id,
                ca.agreement_classification_id,
                ac.name AS agreement_classification_name,
                ca.client_role_id,
                ca.purchased_level_id,
                ca.purchased_on_utc,
                ca.purchased_on_local,
                ca.purchased_on_local_tz,
                ca.start_date_utc,
                ca.start_date_local,
                ca.start_date_local_tz,
                ca.end_date_utc,
                ca.end_date_local,
                ca.end_date_local_tz,
                ca.obligation_start_utc,
                ca.obligation_end_utc,
                ca.client_agreement_status_id,
                s.code AS status_code,
                s.name AS status_name,
                ca.lead_source_id,
                ca.sales_advisor_id,
                ca.is_signed,
                ca.signed_on_utc,
                ca.is_active,
                ca.created_on,
                ca.created_by,
                ca.modified_on,
                ca.modified_by
            FROM client_agreements.client_agreement ca
            JOIN agreements.agreement a
              ON a.agreement_id = ca.agreement_id
            JOIN agreements.agreement_version av
              ON av.agreement_version_id = ca.agreement_version_id
            JOIN agreements.agreement_classification ac
              ON ac.agreement_classification_id = ca.agreement_classification_id
            JOIN agreements.agreement_location al
              ON al.agreement_location_id = ca.agreement_location_id
             AND al.agreement_version_id = ca.agreement_version_id
            JOIN client_agreements.lu_client_agreement_status s
              ON s.client_agreement_status_id = ca.client_agreement_status_id
            WHERE ca.client_role_id = :clientRoleId
              AND al.level_id = (SELECT level_id FROM loc)
              AND a.application_id = :applicationId
              AND (:activeOnly IS FALSE OR ca.is_active = TRUE)
            ORDER BY ca.purchased_on_utc DESC, a.name
            """;

        Map<String, Object> params = Map.of(
                "clientRoleId", clientRoleId,
                "locationId", locationId,
                "applicationId", applicationId,
                "activeOnly", activeOnly
        );

        List<ClientAgreementRow> agreements = namedJdbc.query(sql, params, new ClientAgreementRowMapper());

        if (agreements.isEmpty()) {
            return ResponseEntity.ok(List.of());
        }

        // 2. Fetch promotions & upsell items in bulk for all agreements
        List<UUID> ids = agreements.stream()
                .map(r -> r.clientAgreementId)
                .distinct()
                .toList();

        Map<UUID, List<PromotionRow>> promotionsByAgreement = fetchPromotions(ids);
        Map<UUID, List<UpsellItemRow>> upsellByAgreement = fetchUpsellItems(ids);

        // 3. Map rows to response DTOs
        List<ClientAgreementResponse> response = agreements.stream()
                .map(r -> toResponse(r,
                        promotionsByAgreement.getOrDefault(r.clientAgreementId, List.of()),
                        upsellByAgreement.getOrDefault(r.clientAgreementId, List.of())))
                .toList();

        return ResponseEntity.ok(response);
    }

    private Map<UUID, List<PromotionRow>> fetchPromotions(List<UUID> agreementIds) {
        if (agreementIds.isEmpty()) return Map.of();

        String sql = """
            SELECT
                cap.client_agreement_promotion_id,
                cap.client_agreement_id,
                cap.promotion_version_id,
                pv.version_number,
                p.promotion_id,
                p.name AS promotion_name,
                cap.applied_on,
                cap.discount_amount,
                cap.notes,
                cap.is_active,
                cap.created_on,
                cap.created_by,
                cap.modified_on,
                cap.modified_by
            FROM client_agreements.client_agreement_promotion cap
            JOIN promotions.promotion_version pv
              ON pv.promotion_version_id = cap.promotion_version_id
            JOIN promotions.promotion p
              ON p.promotion_id = pv.promotion_id
            WHERE cap.client_agreement_id = ANY(:ids)
            """;

        Map<String, Object> params = Map.of("ids", agreementIds.toArray(UUID[]::new));

        List<PromotionRow> rows = namedJdbc.query(sql, params, (rs, rowNum) -> {
            var r = new PromotionRow();
            r.clientAgreementPromotionId = getUuid(rs, "client_agreement_promotion_id");
            r.clientAgreementId = getUuid(rs, "client_agreement_id");
            r.promotionVersionId = getUuid(rs, "promotion_version_id");
            r.promotionId = getUuid(rs, "promotion_id");
            r.promotionName = rs.getString("promotion_name");
            r.versionNumber = rs.getInt("version_number");
            r.appliedOn = getOffsetDateTime(rs, "applied_on");
            r.discountAmount = getBigDecimal(rs, "discount_amount");
            r.notes = rs.getString("notes");
            r.isActive = rs.getBoolean("is_active");
            return r;
        });

        return rows.stream().collect(Collectors.groupingBy(r -> r.clientAgreementId));
    }

    private Map<UUID, List<UpsellItemRow>> fetchUpsellItems(List<UUID> agreementIds) {
        if (agreementIds.isEmpty()) return Map.of();

        String sql = """
            SELECT
                caui.client_agreement_upsell_item_id,
                caui.client_agreement_id,
                caui.quantity,
                caui.unit_price,
                caui.discount_amount,
                caui.tax_amount,
                caui.total_amount,
                caui.currency_code,
                caui.item_version_id,
                caui.invoice_id,
                caui.invoice_entity_id,
                caui.is_active,
                caui.created_on,
                caui.created_by,
                caui.modified_on,
                caui.modified_by
            FROM client_agreements.client_agreement_upsell_item caui
            WHERE caui.client_agreement_id = ANY(:ids)
            """;

        Map<String, Object> params = Map.of("ids", agreementIds.toArray(UUID[]::new));

        List<UpsellItemRow> rows = namedJdbc.query(sql, params, (rs, rowNum) -> {
            var r = new UpsellItemRow();
            r.clientAgreementUpsellItemId = getUuid(rs, "client_agreement_upsell_item_id");
            r.clientAgreementId = getUuid(rs, "client_agreement_id");
            r.quantity = rs.getInt("quantity");
            r.unitPrice = getBigDecimal(rs, "unit_price");
            r.discountAmount = getBigDecimal(rs, "discount_amount");
            r.taxAmount = getBigDecimal(rs, "tax_amount");
            r.totalAmount = getBigDecimal(rs, "total_amount");
            r.currencyCode = rs.getString("currency_code");
            r.itemVersionId = getUuid(rs, "item_version_id");
            r.invoiceId = getUuid(rs, "invoice_id");
            r.invoiceEntityId = getUuid(rs, "invoice_entity_id");
            r.isActive = rs.getBoolean("is_active");
            return r;
        });

        return rows.stream().collect(Collectors.groupingBy(r -> r.clientAgreementId));
    }

    private static UUID toUuidOrFail(String s, String field) {
        if (s == null || s.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Missing required param: " + field);
        }
        try {
            return UUID.fromString(s.trim());
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Invalid UUID for " + field + ": " + s);
        }
    }

    /* ─────────────────────── POST endpoint ───────────────────────
     * POST /client-agreements/api/client-agreements
     * Headers: X-Location-Id, X-Actor-Id
     */

    @PostMapping("/client-agreements")
    @Transactional
    public ResponseEntity<ClientAgreementCreateResponse> createClientAgreement(
            @RequestHeader HttpHeaders headers,
            @Valid @RequestBody ClientAgreementCreateRequest req
    ) {
        UUID locationId = mustUuidHeader(headers, HDR_LOCATION_ID);
        UUID actorId = mustUuidHeader(headers, HDR_ACTOR_ID);
        var ctx = requireCtx();
        UUID applicationId = ctx.applicationId(); // not directly stored but used for validation if needed

        // Resolve purchased_level_id from header (commonly your location level)
        UUID purchasedLevelId = req.purchasedLevelId;

        // Resolve statusId either from explicit id or from code (default ACTIVE)
        UUID statusId = resolveStatusId(req.clientAgreementStatusId, req.clientAgreementStatusCode);

        // Basic sanity checks
        if (req.startDateUtc != null && req.endDateUtc != null &&
                req.endDateUtc.isBefore(req.startDateUtc)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "endDateUtc cannot be before startDateUtc");
        }

        if (req.obligationStartUtc != null && req.obligationEndUtc != null &&
                req.obligationEndUtc.isBefore(req.obligationStartUtc)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "obligationEndUtc cannot be before obligationStartUtc");
        }

        // Insert client_agreement
        UUID clientAgreementId;
        try {
            String sql = """
                INSERT INTO client_agreements.client_agreement (
                    agreement_id,
                    agreement_version_id,
                    agreement_location_id,
                    agreement_classification_id,
                    client_role_id,
                    purchased_level_id,
                    purchased_on_utc,
                    purchased_on_local,
                    purchased_on_local_tz,
                    start_date_utc,
                    start_date_local,
                    start_date_local_tz,
                    end_date_utc,
                    end_date_local,
                    end_date_local_tz,
                    obligation_start_utc,
                    obligation_end_utc,
                    client_agreement_status_id,
                    lead_source_id,
                    sales_advisor_id,
                    is_signed,
                    signed_on_utc,
                    created_by
                ) VALUES (
                    :agreementId,
                    :agreementVersionId,
                    :agreementLocationId,
                    :agreementClassificationId,
                    :clientRoleId,
                    :purchasedLevelId,
                    COALESCE(:purchasedOnUtc, now() AT TIME ZONE 'UTC'),
                    :purchasedOnLocal,
                    :purchasedOnLocalTz,
                    COALESCE(:startDateUtc, now() AT TIME ZONE 'UTC'),
                    :startDateLocal,
                    :startDateLocalTz,
                    :endDateUtc,
                    :endDateLocal,
                    :endDateLocalTz,
                    :obligationStartUtc,
                    :obligationEndUtc,
                    :statusId,
                    :leadSourceId,
                    :salesAdvisorId,
                    :isSigned,
                    :signedOnUtc,
                    :createdBy
                )
                RETURNING client_agreement_id
                """;

            MapSqlParameterSource params = new MapSqlParameterSource()
                    .addValue("agreementId", required(req.agreementId, "agreementId"))
                    .addValue("agreementVersionId", required(req.agreementVersionId, "agreementVersionId"))
                    .addValue("agreementLocationId", required(req.agreementLocationId, "agreementLocationId"))
                    .addValue("agreementClassificationId", required(req.agreementClassificationId, "agreementClassificationId"))
                    .addValue("clientRoleId", required(req.clientRoleId, "clientRoleId"))
                    .addValue("purchasedLevelId", purchasedLevelId)
                    .addValue("purchasedOnUtc", req.purchasedOnUtc)
                    .addValue("purchasedOnLocal", req.purchasedOnLocal)
                    .addValue("purchasedOnLocalTz", req.purchasedOnLocalTz)
                    .addValue("startDateUtc", req.startDateUtc)
                    .addValue("startDateLocal", req.startDateLocal)
                    .addValue("startDateLocalTz", req.startDateLocalTz)
                    .addValue("endDateUtc", req.endDateUtc)
                    .addValue("endDateLocal", req.endDateLocal)
                    .addValue("endDateLocalTz", req.endDateLocalTz)
                    .addValue("obligationStartUtc", req.obligationStartUtc)
                    .addValue("obligationEndUtc", req.obligationEndUtc)
                    .addValue("statusId", statusId)
                    .addValue("leadSourceId", req.leadSourceId)
                    .addValue("salesAdvisorId", req.salesAdvisorId)
                    .addValue("isSigned", req.isSigned != null ? req.isSigned : Boolean.FALSE)
                    .addValue("signedOnUtc", req.signedOnUtc)
                    .addValue("createdBy", actorId);

            clientAgreementId = namedJdbc.queryForObject(sql, params, UUID.class);
        } catch (DataIntegrityViolationException ex) {
            // e.g. unique index uq_ca_one_active_per_class violation, FK issues, etc.
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Failed to create client agreement due to data constraint: " + rootMessage(ex),
                    ex
            );
        }

        // Insert promotions.
        // Backward compatible: the frontend sends promotionId, which may contain
        // promotion_applicability_id, promotion_version_id, or master promotion_id.
        if (req.promotions != null && !req.promotions.isEmpty()) {
            String sqlPromo = """
                INSERT INTO client_agreements.client_agreement_promotion (
                    client_agreement_id,
                    promotion_version_id,
                    discount_amount,
                    notes,
                    created_by
                ) VALUES (
                    :clientAgreementId,
                    :promotionVersionId,
                    :discountAmount,
                    :notes,
                    :createdBy
                )
                ON CONFLICT (
                    client_agreement_id,
                    promotion_version_id
                )
                DO UPDATE SET
                    discount_amount = EXCLUDED.discount_amount,
                    notes = EXCLUDED.notes,
                    is_active = TRUE,
                    modified_on = now(),
                    modified_by = EXCLUDED.created_by
                """;

            List<UUID> explicitVersionIds = new ArrayList<>();
            List<UUID> resolveIds = new ArrayList<>();
            for (var p : req.promotions) {
                if (p.promotionVersionId != null) {
                    explicitVersionIds.add(p.promotionVersionId);
                } else if (p.promotionId != null) {
                    resolveIds.add(p.promotionId);
                } else {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                            "promotions[].promotionId or promotions[].promotionVersionId is required");
                }
            }

            Set<UUID> validatedExplicit = validatePromotionVersionIdsBatch(explicitVersionIds);
            Map<UUID, UUID> resolvedByIncoming = resolvePromotionVersionIdsBatch(resolveIds);

            List<Map<String, Object>> batch = new ArrayList<>();
            for (var p : req.promotions) {
                UUID promotionVersionId;
                if (p.promotionVersionId != null) {
                    if (!validatedExplicit.contains(p.promotionVersionId)) {
                        throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                                "Promotion version does not exist or is inactive: " + p.promotionVersionId);
                    }
                    promotionVersionId = p.promotionVersionId;
                } else {
                    promotionVersionId = resolvedByIncoming.get(p.promotionId);
                    if (promotionVersionId == null) {
                        throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                                "No active promotion version found for promotionId: " + p.promotionId);
                    }
                }

                Map<String, Object> row = new HashMap<>();
                row.put("clientAgreementId", clientAgreementId);
                row.put("promotionVersionId", promotionVersionId);
                row.put(
                        "discountAmount",
                        p.discountAmount != null
                                ? p.discountAmount.setScale(2, RoundingMode.HALF_UP)
                                : BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP)
                );
                row.put("notes", p.notes);
                row.put("createdBy", actorId);
                batch.add(row);
            }

            namedJdbc.batchUpdate(
                    sqlPromo,
                    batch.toArray(Map[]::new)
            );
        }

        // Insert upsell items
        if (req.upsellItems != null && !req.upsellItems.isEmpty()) {
            String sqlUpsell = """
                INSERT INTO client_agreements.client_agreement_upsell_item (
                    client_agreement_id,
                    quantity,
                    unit_price,
                    discount_amount,
                    tax_amount,
                    total_amount,
                    currency_code,
                    invoice_id,
                    invoice_entity_id,
                    is_active,
                    item_version_id,
                    created_by
                ) VALUES (
                    :clientAgreementId,
                    :quantity,
                    :unitPrice,
                    :discountAmount,
                    :taxAmount,
                    :totalAmount,
                    :currencyCode,
                    :invoiceId,
                    :invoiceEntityId,
                    TRUE,
                    :itemVersionId,
                    :createdBy
                )
                """;

            List<Map<String, Object>> batch = new ArrayList<>();
            for (var u : req.upsellItems) {
                if (u.quantity == null || u.quantity <= 0) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                            "upsellItems[].quantity must be > 0");
                }
                Map<String, Object> row = new HashMap<>();
                row.put("clientAgreementId", clientAgreementId);
                row.put("quantity", u.quantity);
                row.put("unitPrice", required(u.unitPrice, "upsellItems[].unitPrice"));
                row.put("discountAmount", u.discountAmount != null ? u.discountAmount : BigDecimal.ZERO);
                row.put("taxAmount", u.taxAmount != null ? u.taxAmount : BigDecimal.ZERO);
                row.put("totalAmount", required(u.totalAmount, "upsellItems[].totalAmount"));
                row.put("currencyCode", u.currencyCode != null ? u.currencyCode : "INR");
                row.put("invoiceId", u.invoiceId);
                row.put("invoiceEntityId", u.invoiceEntityId);
                row.put("itemVersionId", u.itemVersionId);
                row.put("createdBy", actorId);
                batch.add(row);
            }
            namedJdbc.batchUpdate(sqlUpsell, batch.toArray(Map[]::new));
        }

        var resp = new ClientAgreementCreateResponse();
        resp.clientAgreementId = clientAgreementId;
        resp.createdOnUtc = nowUtc();

        // Dashboard projection: async after commit (trigger only NOTIFYs now).
        final UUID clientRoleIdForProj = req.clientRoleId;
        if (clientRoleIdForProj != null && TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    Thread.startVirtualThread(() -> {
                        try {
                            jdbc.queryForObject(
                                    "SELECT clients.refresh_client_dashboard_proj(?)",
                                    Object.class,
                                    clientRoleIdForProj);
                        } catch (Exception ex) {
                            log.warn("Async client dashboard projection refresh failed for clientRoleId={}: {}",
                                    clientRoleIdForProj, ex.toString());
                        }
                    });
                }
            });
        }

        return ResponseEntity.status(HttpStatus.CREATED).body(resp);
    }

    /**
     * Resolves an incoming promotion identifier to an active
     * promotions.promotion_version.promotion_version_id.
     *
     * Supported identifiers:
     * 1. promotion_version_id
     * 2. promotion_applicability_id
     * 3. master promotion_id
     */
    private UUID resolvePromotionVersionId(UUID incomingPromotionId) {
        if (incomingPromotionId == null) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "promotions[].promotionId is required"
            );
        }

        String sql = """
            WITH resolved AS (
                SELECT
                    pv.promotion_version_id,
                    1 AS resolution_priority,
                    pv.created_on
                FROM promotions.promotion_version pv
                WHERE pv.promotion_version_id = :incomingPromotionId
                  AND pv.is_active = TRUE

                UNION ALL

                SELECT
                    pa.promotion_version_id,
                    2 AS resolution_priority,
                    pv.created_on
                FROM promotions.promotion_applicability pa
                JOIN promotions.promotion_version pv
                  ON pv.promotion_version_id = pa.promotion_version_id
                WHERE pa.promotion_applicability_id = :incomingPromotionId
                  AND pa.is_active = TRUE
                  AND pv.is_active = TRUE

                UNION ALL

                SELECT
                    pv.promotion_version_id,
                    3 AS resolution_priority,
                    pv.created_on
                FROM promotions.promotion_version pv
                WHERE pv.promotion_id = :incomingPromotionId
                  AND pv.is_active = TRUE
            )
            SELECT promotion_version_id
            FROM resolved
            ORDER BY
                resolution_priority,
                created_on DESC NULLS LAST
            LIMIT 1
            """;

        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("incomingPromotionId", incomingPromotionId);

        List<UUID> matches = namedJdbc.query(
                sql,
                params,
                (rs, rowNum) ->
                        rs.getObject("promotion_version_id", UUID.class)
        );

        if (matches.isEmpty()) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "No active promotion version found for promotionId: "
                            + incomingPromotionId
            );
        }

        return matches.get(0);
    }

    private UUID validatePromotionVersionId(UUID promotionVersionId) {
        if (promotionVersionId == null) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "promotions[].promotionVersionId is required"
            );
        }

        String sql = """
            SELECT pv.promotion_version_id
            FROM promotions.promotion_version pv
            WHERE pv.promotion_version_id = :promotionVersionId
              AND pv.is_active = TRUE
            LIMIT 1
            """;

        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("promotionVersionId", promotionVersionId);

        List<UUID> matches = namedJdbc.query(
                sql,
                params,
                (rs, rowNum) ->
                        rs.getObject("promotion_version_id", UUID.class)
        );

        if (matches.isEmpty()) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Promotion version does not exist or is inactive: "
                            + promotionVersionId
            );
        }

        return matches.get(0);
    }

    private UUID resolveStatusId(UUID explicitId, String code) {
        if (explicitId != null) return explicitId;

        String statusCode = (code == null || code.isBlank()) ? "ACTIVE" : code.trim().toUpperCase(Locale.ROOT);
        UUID cached = STATUS_ID_BY_CODE.get(statusCode);
        if (cached != null) {
            return cached;
        }

        String sql = """
            SELECT client_agreement_status_id
            FROM client_agreements.lu_client_agreement_status
            WHERE code = :code
              AND is_active = TRUE
            """;

        try {
            UUID id = namedJdbc.queryForObject(sql, Map.of("code", statusCode), UUID.class);
            if (id != null) {
                STATUS_ID_BY_CODE.put(statusCode, id);
            }
            return id;
        } catch (EmptyResultDataAccessException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Unknown client agreement status code: " + statusCode);
        }
    }

    private Set<UUID> validatePromotionVersionIdsBatch(List<UUID> promotionVersionIds) {
        if (promotionVersionIds == null || promotionVersionIds.isEmpty()) {
            return Set.of();
        }
        String sql = """
            SELECT pv.promotion_version_id
            FROM promotions.promotion_version pv
            WHERE pv.promotion_version_id IN (:ids)
              AND pv.is_active = TRUE
            """;
        List<UUID> matches = namedJdbc.query(
                sql,
                new MapSqlParameterSource("ids", promotionVersionIds),
                (rs, rowNum) -> rs.getObject("promotion_version_id", UUID.class));
        return new HashSet<>(matches);
    }


    /**
     * Batch-resolve incoming promotion identifiers (version / applicability / master id)
     * to active promotion_version_id. Same priority rules as single-id resolve.
     */
    private Map<UUID, UUID> resolvePromotionVersionIdsBatch(List<UUID> incomingIds) {
        if (incomingIds == null || incomingIds.isEmpty()) {
            return Map.of();
        }
        String sql = """
            WITH incoming AS (
              SELECT DISTINCT unnest(?::uuid[]) AS incoming_id
            ),
            resolved AS (
                SELECT i.incoming_id, pv.promotion_version_id, 1 AS resolution_priority, pv.created_on
                FROM incoming i
                JOIN promotions.promotion_version pv
                  ON pv.promotion_version_id = i.incoming_id AND pv.is_active = TRUE
                UNION ALL
                SELECT i.incoming_id, pa.promotion_version_id, 2 AS resolution_priority, pv.created_on
                FROM incoming i
                JOIN promotions.promotion_applicability pa
                  ON pa.promotion_applicability_id = i.incoming_id AND pa.is_active = TRUE
                JOIN promotions.promotion_version pv
                  ON pv.promotion_version_id = pa.promotion_version_id AND pv.is_active = TRUE
                UNION ALL
                SELECT i.incoming_id, pv.promotion_version_id, 3 AS resolution_priority, pv.created_on
                FROM incoming i
                JOIN promotions.promotion_version pv
                  ON pv.promotion_id = i.incoming_id AND pv.is_active = TRUE
            ),
            ranked AS (
              SELECT incoming_id, promotion_version_id,
                     ROW_NUMBER() OVER (
                       PARTITION BY incoming_id
                       ORDER BY resolution_priority, created_on DESC NULLS LAST
                     ) AS rn
              FROM resolved
            )
            SELECT incoming_id, promotion_version_id
            FROM ranked
            WHERE rn = 1
            """;

        UUID[] arr = incomingIds.toArray(UUID[]::new);
        List<Map.Entry<UUID, UUID>> rows = jdbc.query(sql, ps -> {
            ps.setArray(1, ps.getConnection().createArrayOf("uuid", arr));
        }, (rs, rowNum) -> Map.entry(
                rs.getObject("incoming_id", UUID.class),
                rs.getObject("promotion_version_id", UUID.class)
        ));

        Map<UUID, UUID> out = new HashMap<>();
        for (Map.Entry<UUID, UUID> e : rows) {
            out.put(e.getKey(), e.getValue());
        }
        return out;
    }

    private static String rootMessage(Throwable ex) {
        Throwable t = ex;
        while (t.getCause() != null) t = t.getCause();
        return t.getMessage();
    }

    /* ─────────────────────── Row / DTO mapping helpers ─────────────────────── */

    private static class ClientAgreementRow {
        UUID clientAgreementId;
        String clientAgreementCode;
        UUID agreementId;
        String agreementName;
        UUID agreementVersionId;
        Integer versionNumber;
        UUID agreementLocationId;
        UUID agreementLevelId;
        UUID agreementClassificationId;
        String agreementClassificationName;
        UUID clientRoleId;
        UUID purchasedLevelId;
        OffsetDateTime purchasedOnUtc;
        java.time.LocalDateTime purchasedOnLocal;
        String purchasedOnLocalTz;
        OffsetDateTime startDateUtc;
        java.time.LocalDateTime startDateLocal;
        String startDateLocalTz;
        OffsetDateTime endDateUtc;
        java.time.LocalDateTime endDateLocal;
        String endDateLocalTz;
        OffsetDateTime obligationStartUtc;
        OffsetDateTime obligationEndUtc;
        UUID statusId;
        String statusCode;
        String statusName;
        UUID leadSourceId;
        UUID salesAdvisorId;
        Boolean isSigned;
        OffsetDateTime signedOnUtc;
        Boolean isActive;
        OffsetDateTime createdOn;
        UUID createdBy;
        OffsetDateTime modifiedOn;
        UUID modifiedBy;
    }

    private static class ClientAgreementRowMapper implements RowMapper<ClientAgreementRow> {
        @Override
        public ClientAgreementRow mapRow(ResultSet rs, int rowNum) throws SQLException {
            var r = new ClientAgreementRow();
            r.clientAgreementId = getUuid(rs, "client_agreement_id");
            r.clientAgreementCode = rs.getString("client_agreement_code");
            r.agreementId = getUuid(rs, "agreement_id");
            r.agreementName = rs.getString("agreement_name");
            r.agreementVersionId = getUuid(rs, "agreement_version_id");
            r.versionNumber = rs.getInt("version_number");
            r.agreementLocationId = getUuid(rs, "agreement_location_id");
            r.agreementLevelId = getUuid(rs, "agreement_level_id");
            r.agreementClassificationId = getUuid(rs, "agreement_classification_id");
            r.agreementClassificationName = rs.getString("agreement_classification_name");
            r.clientRoleId = getUuid(rs, "client_role_id");
            r.purchasedLevelId = getUuid(rs, "purchased_level_id");
            r.purchasedOnUtc = getOffsetDateTime(rs, "purchased_on_utc");
            r.purchasedOnLocal = rs.getTimestamp("purchased_on_local") != null
                    ? rs.getTimestamp("purchased_on_local").toLocalDateTime()
                    : null;
            r.purchasedOnLocalTz = rs.getString("purchased_on_local_tz");
            r.startDateUtc = getOffsetDateTime(rs, "start_date_utc");
            r.startDateLocal = rs.getTimestamp("start_date_local") != null
                    ? rs.getTimestamp("start_date_local").toLocalDateTime()
                    : null;
            r.startDateLocalTz = rs.getString("start_date_local_tz");
            r.endDateUtc = getOffsetDateTime(rs, "end_date_utc");
            r.endDateLocal = rs.getTimestamp("end_date_local") != null
                    ? rs.getTimestamp("end_date_local").toLocalDateTime()
                    : null;
            r.endDateLocalTz = rs.getString("end_date_local_tz");
            r.obligationStartUtc = getOffsetDateTime(rs, "obligation_start_utc");
            r.obligationEndUtc = getOffsetDateTime(rs, "obligation_end_utc");
            r.statusId = getUuid(rs, "client_agreement_status_id");
            r.statusCode = rs.getString("status_code");
            r.statusName = rs.getString("status_name");
            r.leadSourceId = getUuid(rs, "lead_source_id");
            r.salesAdvisorId = getUuid(rs, "sales_advisor_id");
            r.isSigned = (Boolean) rs.getObject("is_signed");
            r.signedOnUtc = getOffsetDateTime(rs, "signed_on_utc");
            r.isActive = (Boolean) rs.getObject("is_active");
            r.createdOn = getOffsetDateTime(rs, "created_on");
            r.createdBy = getUuid(rs, "created_by");
            r.modifiedOn = getOffsetDateTime(rs, "modified_on");
            r.modifiedBy = getUuid(rs, "modified_by");
            return r;
        }
    }

    private static class PromotionRow {
        UUID clientAgreementPromotionId;
        UUID clientAgreementId;
        UUID promotionVersionId;
        UUID promotionId;
        String promotionName;
        Integer versionNumber;
        OffsetDateTime appliedOn;
        BigDecimal discountAmount;
        String notes;
        Boolean isActive;
    }

    private static class UpsellItemRow {
        UUID clientAgreementUpsellItemId;
        UUID clientAgreementId;
        Integer quantity;
        BigDecimal unitPrice;
        BigDecimal discountAmount;
        BigDecimal taxAmount;
        BigDecimal totalAmount;
        String currencyCode;
        UUID itemVersionId;
        UUID invoiceId;
        UUID invoiceEntityId;
        Boolean isActive;
    }

    private static UUID getUuid(ResultSet rs, String col) throws SQLException {
        Object o = rs.getObject(col);
        if (o == null) return null;
        if (o instanceof UUID u) return u;
        return UUID.fromString(o.toString());
    }

    private static OffsetDateTime getOffsetDateTime(ResultSet rs, String col) throws SQLException {
        var ts = rs.getTimestamp(col);
        if (ts == null) return null;
        return ts.toInstant().atOffset(ZoneOffset.UTC);
    }

    private static BigDecimal getBigDecimal(ResultSet rs, String col) throws SQLException {
        var bd = rs.getBigDecimal(col);
        return bd != null ? bd : BigDecimal.ZERO;
    }

    private static ClientAgreementResponse toResponse(
            ClientAgreementRow row,
            List<PromotionRow> promotions,
            List<UpsellItemRow> upsells
    ) {
        var r = new ClientAgreementResponse();
        r.clientAgreementId = row.clientAgreementId;
        r.clientAgreementCode = row.clientAgreementCode;
        r.agreementId = row.agreementId;
        r.agreementName = row.agreementName;
        r.agreementVersionId = row.agreementVersionId;
        r.versionNumber = row.versionNumber;
        r.agreementLocationId = row.agreementLocationId;
        r.agreementLevelId = row.agreementLevelId;
        r.agreementClassificationId = row.agreementClassificationId;
        r.agreementClassificationName = row.agreementClassificationName;
        r.clientRoleId = row.clientRoleId;
        r.purchasedLevelId = row.purchasedLevelId;
        r.purchasedOnUtc = row.purchasedOnUtc;
        r.purchasedOnLocal = row.purchasedOnLocal;
        r.purchasedOnLocalTz = row.purchasedOnLocalTz;
        r.startDateUtc = row.startDateUtc;
        r.startDateLocal = row.startDateLocal;
        r.startDateLocalTz = row.startDateLocalTz;
        r.endDateUtc = row.endDateUtc;
        r.endDateLocal = row.endDateLocal;
        r.endDateLocalTz = row.endDateLocalTz;
        r.obligationStartUtc = row.obligationStartUtc;
        r.obligationEndUtc = row.obligationEndUtc;
        r.clientAgreementStatusId = row.statusId;
        r.clientAgreementStatusCode = row.statusCode;
        r.clientAgreementStatusName = row.statusName;
        r.leadSourceId = row.leadSourceId;
        r.salesAdvisorId = row.salesAdvisorId;
        r.isSigned = row.isSigned;
        r.signedOnUtc = row.signedOnUtc;
        r.isActive = row.isActive;

        r.promotions = promotions.stream().map(p -> {
            var dto = new PromotionDto();
            dto.clientAgreementPromotionId = p.clientAgreementPromotionId;
            dto.promotionVersionId = p.promotionVersionId;
            dto.promotionId = p.promotionId;
            dto.promotionName = p.promotionName;
            dto.versionNumber = p.versionNumber;
            dto.appliedOn = p.appliedOn;
            dto.discountAmount = p.discountAmount;
            dto.notes = p.notes;
            dto.isActive = p.isActive;
            return dto;
        }).toList();

        r.upsellItems = upsells.stream().map(u -> {
            var dto = new UpsellItemDto();
            dto.clientAgreementUpsellItemId = u.clientAgreementUpsellItemId;
            dto.quantity = u.quantity;
            dto.unitPrice = u.unitPrice;
            dto.discountAmount = u.discountAmount;
            dto.taxAmount = u.taxAmount;
            dto.totalAmount = u.totalAmount;
            dto.currencyCode = u.currencyCode;
            dto.itemVersionId = u.itemVersionId;
            dto.invoiceId = u.invoiceId;
            dto.invoiceEntityId = u.invoiceEntityId;
            dto.isActive = u.isActive;
            return dto;
        }).toList();

        return r;
    }

    /* ─────────────────────── Request / Response DTOs ─────────────────────── */

    public static class ClientAgreementCreateRequest {

        @NotNull
        public UUID agreementId;

        @NotNull
        public UUID agreementVersionId;

        @NotNull
        public UUID agreementLocationId;

        @NotNull
        public UUID agreementClassificationId;

        @NotNull
        public UUID clientRoleId;

        // Optional: allow client to override; otherwise derived from header
        public UUID purchasedLevelId;

        public OffsetDateTime purchasedOnUtc;
        public java.time.LocalDateTime purchasedOnLocal;
        public String purchasedOnLocalTz;

        public OffsetDateTime startDateUtc;
        public java.time.LocalDateTime startDateLocal;
        public String startDateLocalTz;

        public OffsetDateTime endDateUtc;
        public java.time.LocalDateTime endDateLocal;
        public String endDateLocalTz;

        public OffsetDateTime obligationStartUtc;
        public OffsetDateTime obligationEndUtc;

        // Either ID or CODE (if none provided, default is code ACTIVE)
        public UUID clientAgreementStatusId;
        public String clientAgreementStatusCode;

        public UUID leadSourceId;
        public UUID salesAdvisorId;

        public Boolean isSigned;
        public OffsetDateTime signedOnUtc;

        @Valid
        public List<PromotionCreateDto> promotions;

        @Valid
        public List<UpsellItemCreateDto> upsellItems;
    }

    public static class PromotionCreateDto {
        /**
         * Current frontend field. It may contain:
         * - promotion_applicability_id
         * - promotion_version_id
         * - master promotion_id
         */
        public UUID promotionId;

        /**
         * Optional direct promotion version ID for backward/forward compatibility.
         * When present, it takes precedence over promotionId.
         */
        public UUID promotionVersionId;

        @DecimalMin(value = "0.00")
        public BigDecimal discountAmount;

        public String notes;
    }

    public static class UpsellItemCreateDto {
        @NotNull
        @Min(1)
        public Integer quantity;

        @NotNull
        @DecimalMin(value = "0.00")
        public BigDecimal unitPrice;

        @DecimalMin(value = "0.00")
        public BigDecimal discountAmount;

        @DecimalMin(value = "0.00")
        public BigDecimal taxAmount;

        @NotNull
        @DecimalMin(value = "0.00")
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

    public static class ClientAgreementResponse {
        public UUID clientAgreementId;
        public String clientAgreementCode;
        public UUID agreementId;
        public String agreementName;
        public UUID agreementVersionId;
        public Integer versionNumber;
        public UUID agreementLocationId;
        public UUID agreementLevelId;
        public UUID agreementClassificationId;
        public String agreementClassificationName;
        public UUID clientRoleId;
        public UUID purchasedLevelId;
        public OffsetDateTime purchasedOnUtc;
        public java.time.LocalDateTime purchasedOnLocal;
        public String purchasedOnLocalTz;
        public OffsetDateTime startDateUtc;
        public java.time.LocalDateTime startDateLocal;
        public String startDateLocalTz;
        public OffsetDateTime endDateUtc;
        public java.time.LocalDateTime endDateLocal;
        public String endDateLocalTz;
        public OffsetDateTime obligationStartUtc;
        public OffsetDateTime obligationEndUtc;
        public UUID clientAgreementStatusId;
        public String clientAgreementStatusCode;
        public String clientAgreementStatusName;
        public UUID leadSourceId;
        public UUID salesAdvisorId;
        public Boolean isSigned;
        public OffsetDateTime signedOnUtc;
        public Boolean isActive;

        public List<PromotionDto> promotions;
        public List<UpsellItemDto> upsellItems;
    }

    public static class PromotionDto {
        public UUID clientAgreementPromotionId;
        public UUID promotionVersionId;
        public UUID promotionId;
        public String promotionName;
        public Integer versionNumber;
        public OffsetDateTime appliedOn;
        public BigDecimal discountAmount;
        public String notes;
        public Boolean isActive;
    }

    public static class UpsellItemDto {
        public UUID clientAgreementUpsellItemId;
        public Integer quantity;
        public BigDecimal unitPrice;
        public BigDecimal discountAmount;
        public BigDecimal taxAmount;
        public BigDecimal totalAmount;
        public String currencyCode;
        public UUID itemVersionId;
        public UUID invoiceId;
        public UUID invoiceEntityId;
        public Boolean isActive;
    }
}