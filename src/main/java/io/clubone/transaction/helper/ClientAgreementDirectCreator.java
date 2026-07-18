package io.clubone.transaction.helper;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

import io.clubone.transaction.helper.ClientAgreementCreationHelper.ClientAgreementCreateRequest;
import io.clubone.transaction.helper.ClientAgreementCreationHelper.PromotionCreateDto;
import io.clubone.transaction.security.TenantContext;

/**
 * High-load path: insert client_agreement in the shared DB from transaction-service.
 * Skips HTTP → API gateway → agreement-service filter/pool entirely.
 */
@Component
public class ClientAgreementDirectCreator {

	private static final Logger log = LoggerFactory.getLogger(ClientAgreementDirectCreator.class);

	private final NamedParameterJdbcTemplate namedJdbc;
	private final TransactionTemplate tx;
	private final Cache<String, UUID> statusIdByCode = Caffeine.newBuilder()
			.maximumSize(64)
			.expireAfterWrite(1, TimeUnit.HOURS)
			.build();

	public ClientAgreementDirectCreator(
			NamedParameterJdbcTemplate namedJdbc,
			PlatformTransactionManager transactionManager) {
		this.namedJdbc = namedJdbc;
		this.tx = new TransactionTemplate(transactionManager);
	}

	public UUID create(ClientAgreementCreateRequest req) {
		long t0 = System.nanoTime();
		UUID createdBy = resolveCreatedBy(req);
		UUID statusId = resolveStatusId(req.getClientAgreementStatusId(), req.getClientAgreementStatusCode());

		UUID clientAgreementId = tx.execute(status -> {
			namedJdbc.getJdbcTemplate().execute(
					"SET LOCAL statement_timeout = '5s'; SET LOCAL lock_timeout = '2s'");
			UUID id = insertClientAgreement(req, statusId, createdBy);
			insertPromotions(id, req.getPromotions(), createdBy);
			return id;
		});

		long elapsedMs = (System.nanoTime() - t0) / 1_000_000L;
		if (elapsedMs >= 500) {
			log.warn("[client-agreement-direct] slow elapsedMs={} clientAgreementId={} clientRoleId={}",
					elapsedMs, clientAgreementId, req.getClientRoleId());
		} else {
			log.info("[client-agreement-direct] elapsedMs={} clientAgreementId={}",
					elapsedMs, clientAgreementId);
		}
		return clientAgreementId;
	}

	private UUID insertClientAgreement(ClientAgreementCreateRequest req, UUID statusId, UUID createdBy) {
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
				.addValue("agreementId", required(req.getAgreementId(), "agreementId"))
				.addValue("agreementVersionId", required(req.getAgreementVersionId(), "agreementVersionId"))
				.addValue("agreementLocationId", required(req.getAgreementLocationId(), "agreementLocationId"))
				.addValue("agreementClassificationId",
						required(req.getAgreementClassificationId(), "agreementClassificationId"))
				.addValue("clientRoleId", required(req.getClientRoleId(), "clientRoleId"))
				.addValue("purchasedLevelId", req.getPurchasedLevelId())
				.addValue("purchasedOnUtc", req.getPurchasedOnUtc())
				.addValue("purchasedOnLocal", req.getPurchasedOnLocal())
				.addValue("purchasedOnLocalTz", req.getPurchasedOnLocalTz())
				.addValue("startDateUtc", req.getStartDateUtc())
				.addValue("startDateLocal", req.getStartDateLocal())
				.addValue("startDateLocalTz", req.getStartDateLocalTz())
				.addValue("endDateUtc", req.getEndDateUtc())
				.addValue("endDateLocal", req.getEndDateLocal())
				.addValue("endDateLocalTz", req.getEndDateLocalTz())
				.addValue("obligationStartUtc", req.getObligationStartUtc())
				.addValue("obligationEndUtc", req.getObligationEndUtc())
				.addValue("statusId", statusId)
				.addValue("leadSourceId", req.getLeadSourceId())
				.addValue("salesAdvisorId", req.getSalesAdvisorId())
				.addValue("isSigned", req.getIsSigned() != null ? req.getIsSigned() : Boolean.FALSE)
				.addValue("signedOnUtc", req.getSignedOnUtc())
				.addValue("createdBy", createdBy);

		try {
			return namedJdbc.queryForObject(sql, params, UUID.class);
		} catch (DataIntegrityViolationException ex) {
			throw new ResponseStatusException(HttpStatus.CONFLICT,
					"Failed to create client agreement due to data constraint: " + rootMessage(ex), ex);
		}
	}

	private void insertPromotions(UUID clientAgreementId, List<PromotionCreateDto> promotions, UUID createdBy) {
		if (promotions == null || promotions.isEmpty()) {
			return;
		}
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
				ON CONFLICT (client_agreement_id, promotion_version_id)
				DO UPDATE SET
				    discount_amount = EXCLUDED.discount_amount,
				    notes = EXCLUDED.notes,
				    is_active = TRUE,
				    modified_on = now(),
				    modified_by = EXCLUDED.created_by
				""";

		for (PromotionCreateDto p : promotions) {
			UUID promotionVersionId = p.getPromotionVersionId() != null
					? p.getPromotionVersionId()
					: resolvePromotionVersionId(p.getPromotionId());
			MapSqlParameterSource row = new MapSqlParameterSource()
					.addValue("clientAgreementId", clientAgreementId)
					.addValue("promotionVersionId", promotionVersionId)
					.addValue("discountAmount",
							p.getDiscountAmount() != null
									? p.getDiscountAmount().setScale(2, RoundingMode.HALF_UP)
									: BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP))
					.addValue("notes", p.getNotes())
					.addValue("createdBy", createdBy);
			namedJdbc.update(sqlPromo, row);
		}
	}

	private UUID resolvePromotionVersionId(UUID incomingPromotionId) {
		if (incomingPromotionId == null) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
					"promotions[].promotionId or promotionVersionId is required");
		}
		String sql = """
				WITH resolved AS (
				    SELECT pv.promotion_version_id, 1 AS resolution_priority, pv.created_on
				    FROM promotions.promotion_version pv
				    WHERE pv.promotion_version_id = :incomingPromotionId AND pv.is_active = TRUE
				    UNION ALL
				    SELECT pa.promotion_version_id, 2 AS resolution_priority, pv.created_on
				    FROM promotions.promotion_applicability pa
				    JOIN promotions.promotion_version pv ON pv.promotion_version_id = pa.promotion_version_id
				    WHERE pa.promotion_applicability_id = :incomingPromotionId
				      AND pa.is_active = TRUE AND pv.is_active = TRUE
				    UNION ALL
				    SELECT pv.promotion_version_id, 3 AS resolution_priority, pv.created_on
				    FROM promotions.promotion_version pv
				    WHERE pv.promotion_id = :incomingPromotionId AND pv.is_active = TRUE
				)
				SELECT promotion_version_id
				FROM resolved
				ORDER BY resolution_priority, created_on DESC NULLS LAST
				LIMIT 1
				""";
		List<UUID> matches = namedJdbc.query(sql,
				new MapSqlParameterSource("incomingPromotionId", incomingPromotionId),
				(rs, i) -> rs.getObject("promotion_version_id", UUID.class));
		if (matches.isEmpty()) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
					"No active promotion version found for promotionId: " + incomingPromotionId);
		}
		return matches.get(0);
	}

	private UUID resolveStatusId(UUID explicitId, String code) {
		if (explicitId != null) {
			return explicitId;
		}
		String statusCode = (code == null || code.isBlank()) ? "ACTIVE" : code.trim().toUpperCase(Locale.ROOT);
		return statusIdByCode.get(statusCode, key -> {
			try {
				UUID id = namedJdbc.queryForObject("""
						SELECT client_agreement_status_id
						FROM client_agreements.lu_client_agreement_status
						WHERE code = :code AND is_active = TRUE
						""", Map.of("code", key), UUID.class);
				if (id == null) {
					throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
							"Unknown client agreement status code: " + key);
				}
				return id;
			} catch (EmptyResultDataAccessException ex) {
				throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
						"Unknown client agreement status code: " + key);
			}
		});
	}

	/**
	 * Staff POS: prefer authenticated {@link TenantContext} actor.
	 * Public remote-close / join portal: no X-Actor-Id — use invoice {@code createdBy}
	 * (system/POS fallback actor already present on the request body).
	 */
	private static UUID resolveCreatedBy(ClientAgreementCreateRequest req) {
		TenantContext ctx = TenantContext.get();
		if (ctx != null && ctx.applicationUserId() != null) {
			return ctx.applicationUserId();
		}
		if (req != null && req.getCreatedBy() != null) {
			return req.getCreatedBy();
		}
		throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
				"X-Actor-Id / tenant context is required for direct client-agreement create "
						+ "(or supply createdBy on the invoice request for remote/public purchase)");
	}

	private static <T> T required(T value, String name) {
		if (value == null) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, name + " is required");
		}
		return value;
	}

	private static String rootMessage(Throwable ex) {
		Throwable t = ex;
		while (t.getCause() != null) {
			t = t.getCause();
		}
		return t.getMessage() != null ? t.getMessage() : ex.getMessage();
	}
}
