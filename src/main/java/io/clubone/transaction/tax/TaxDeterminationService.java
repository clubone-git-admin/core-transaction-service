package io.clubone.transaction.tax;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Selects enabled tax provider (INTERNAL by default; Avalara/etc. when configured).
 */
@Service
public class TaxDeterminationService {

	private final List<TaxProvider> providers;
	private final NamedParameterJdbcTemplate jdbc;
	private final InternalTaxProvider internalTaxProvider;

	public TaxDeterminationService(
			List<TaxProvider> providers,
			@Qualifier("cluboneNamedJdbcTemplate") NamedParameterJdbcTemplate jdbc,
			InternalTaxProvider internalTaxProvider
	) {
		this.providers = providers;
		this.jdbc = jdbc;
		this.internalTaxProvider = internalTaxProvider;
	}

	public TaxDeterminationResult determine(TaxDeterminationRequest request) {
		TaxProvider provider = resolveProvider(request.getApplicationId());
		TaxDeterminationResult result = provider.determine(request);
		persistAudit(request, result);
		return result;
	}

	private TaxProvider resolveProvider(UUID applicationId) {
		if (applicationId == null) {
			return internalTaxProvider;
		}
		String sql = """
				SELECT provider_code
				FROM finance.tax_provider_config
				WHERE application_id = :appId
				  AND COALESCE(is_active, true) = true
				  AND COALESCE(is_enabled, false) = true
				ORDER BY CASE WHEN is_default THEN 0 ELSE 1 END, priority ASC
				LIMIT 1
				""";
		List<String> codes = jdbc.query(sql, Map.of("appId", applicationId),
				(rs, i) -> rs.getString("provider_code"));
		if (codes.isEmpty()) {
			return internalTaxProvider;
		}
		String code = codes.get(0);
		return providers.stream()
				.filter(p -> code.equalsIgnoreCase(p.providerCode()))
				.filter(p -> p.supports(applicationId))
				.max(Comparator.comparingInt(p -> "INTERNAL".equals(p.providerCode()) ? 0 : 1))
				.orElse(internalTaxProvider);
	}

	private void persistAudit(TaxDeterminationRequest req, TaxDeterminationResult result) {
		if (result == null) return;
		try {
			if (result.getComponents() == null || result.getComponents().isEmpty()) {
				insertAuditRow(req, result, null, null, null, result.getTaxAmount());
				return;
			}
			for (TaxDeterminationResult.TaxComponent c : result.getComponents()) {
				insertAuditRow(req, result, c.getTaxRateId(), c.getTaxRateAllocationId(),
						c.getPercentage(), c.getAmount());
			}
		} catch (Exception ignore) {
			// Audit must never block checkout.
		}
	}

	private void insertAuditRow(
			TaxDeterminationRequest req,
			TaxDeterminationResult result,
			UUID taxRateId,
			UUID allocationId,
			java.math.BigDecimal pct,
			java.math.BigDecimal amount
	) {
		String sql = """
				INSERT INTO finance.tax_determination_audit (
				  tax_determination_audit_id, application_id, source_type, source_id,
				  item_id, level_id, client_role_id,
				  tax_group_id, catalog_tax_assignment_id, tax_rate_id, tax_rate_allocation_id,
				  matched_level_id, tax_percentage, taxable_amount, tax_amount,
				  resolution_reason, tax_exempt_id, is_tax_inclusive, provider_code,
				  as_of_date, created_by, created_on
				) VALUES (
				  gen_random_uuid(), :appId, :sourceType, :sourceId,
				  :itemId, :levelId, :clientRoleId,
				  :taxGroupId, :assignmentId, :taxRateId, :allocationId,
				  :matchedLevelId, :pct, :taxable, :taxAmount,
				  :reason, :exemptId, :inclusive, :provider,
				  :asOf, :actorId, now()
				)
				""";
		UUID appId = req.getApplicationId();
		if (appId == null) {
			appId = lookupAppId(req.getItemId());
		}
		if (appId == null) return;

		jdbc.update(sql, new org.springframework.jdbc.core.namedparam.MapSqlParameterSource()
				.addValue("appId", appId)
				.addValue("sourceType", req.getSourceType() == null ? "UNKNOWN" : req.getSourceType())
				.addValue("sourceId", req.getSourceId())
				.addValue("itemId", req.getItemId())
				.addValue("levelId", req.getLevelId())
				.addValue("clientRoleId", req.getClientRoleId())
				.addValue("taxGroupId", result.getTaxGroupId())
				.addValue("assignmentId", result.getCatalogTaxAssignmentId())
				.addValue("taxRateId", taxRateId)
				.addValue("allocationId", allocationId)
				.addValue("matchedLevelId", result.getMatchedLevelId())
				.addValue("pct", pct == null ? java.math.BigDecimal.ZERO : pct)
				.addValue("taxable", result.getTaxableAmount())
				.addValue("taxAmount", amount == null ? java.math.BigDecimal.ZERO : amount)
				.addValue("reason", result.getReason().name())
				.addValue("exemptId", result.getTaxExemptId())
				.addValue("inclusive", result.isTaxInclusive())
				.addValue("provider", result.getProviderCode())
				.addValue("asOf", req.getAsOfDate())
				.addValue("actorId", req.getActorId()));
	}

	private UUID lookupAppId(UUID itemId) {
		if (itemId == null) return null;
		List<UUID> ids = jdbc.query(
				"SELECT application_id FROM items.item WHERE item_id = :id LIMIT 1",
				Map.of("id", itemId),
				(rs, i) -> (UUID) rs.getObject("application_id"));
		return ids.isEmpty() ? null : ids.get(0);
	}
}
