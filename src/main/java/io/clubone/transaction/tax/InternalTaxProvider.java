package io.clubone.transaction.tax;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import io.clubone.transaction.dao.TransactionDAO;
import io.clubone.transaction.vo.TaxRateAllocationDTO;

/**
 * INTERNAL tax provider: catalog assignment + level-ancestor rates + exempt levels.
 */
@Service
public class InternalTaxProvider implements TaxProvider {

	private final NamedParameterJdbcTemplate jdbc;
	private final TransactionDAO transactionDAO;

	public InternalTaxProvider(
			@Qualifier("cluboneNamedJdbcTemplate") NamedParameterJdbcTemplate jdbc,
			TransactionDAO transactionDAO) {
		this.jdbc = jdbc;
		this.transactionDAO = transactionDAO;
	}

	@Override
	public String providerCode() {
		return "INTERNAL";
	}

	@Override
	public boolean supports(UUID applicationId) {
		return true;
	}

	@Override
	public TaxDeterminationResult determine(TaxDeterminationRequest req) {
		if (req.getItemId() == null || req.getLevelId() == null) {
			return TaxDeterminationResult.zero(TaxDeterminationResult.Reason.NO_RATE, null);
		}

		UUID exemptId = resolveClientExemptId(req.getClientRoleId());
		if (exemptId != null && isExemptAtLevel(exemptId, req.getLevelId())) {
			BigDecimal base = TaxMath.exclusiveTaxableBase(req.getUnitPrice(), req.getQuantity(), req.getDiscountAmount());
			return new TaxDeterminationResult(
					null, null, null, exemptId, false,
					TaxDeterminationResult.Reason.EXEMPT_ZERO, providerCode(),
					List.of(), base, BigDecimal.ZERO);
		}

		TaxClassResolution cls = resolveTaxClass(req.getItemId(), req.getLevelId(), req.getAsOfDate());
		if (cls.getTaxGroupId() == null) {
			BigDecimal base = TaxMath.exclusiveTaxableBase(req.getUnitPrice(), req.getQuantity(), req.getDiscountAmount());
			return new TaxDeterminationResult(
					null, null, null, exemptId, false,
					TaxDeterminationResult.Reason.NO_RATE, providerCode(),
					List.of(), base, BigDecimal.ZERO);
		}

		List<TaxRateAllocationDTO> allocs = cls.getAllocations();
		if (allocs == null || allocs.isEmpty()) {
			allocs = transactionDAO.getTaxRatesByGroupAndLevel(cls.getTaxGroupId(), req.getLevelId());
		}
		if (allocs == null || allocs.isEmpty()) {
			BigDecimal base = TaxMath.exclusiveTaxableBase(req.getUnitPrice(), req.getQuantity(), req.getDiscountAmount());
			return new TaxDeterminationResult(
					cls.getTaxGroupId(), cls.getCatalogTaxAssignmentId(), cls.getMatchedLevelId(),
					exemptId, cls.isTaxInclusive(),
					TaxDeterminationResult.Reason.NO_RATE, providerCode(),
					List.of(), base, BigDecimal.ZERO);
		}

		BigDecimal lineBase = TaxMath.exclusiveTaxableBase(req.getUnitPrice(), req.getQuantity(), req.getDiscountAmount());
		List<TaxDeterminationResult.TaxComponent> components =
				TaxMath.splitByAllocations(lineBase, allocs, cls.isTaxInclusive());
		BigDecimal taxAmount = TaxMath.sumTax(components);
		BigDecimal taxable = cls.isTaxInclusive()
				? TaxMath.scale2(lineBase.subtract(taxAmount))
				: lineBase;

		return new TaxDeterminationResult(
				cls.getTaxGroupId(),
				cls.getCatalogTaxAssignmentId(),
				cls.getMatchedLevelId(),
				exemptId,
				cls.isTaxInclusive(),
				cls.getReason(),
				providerCode(),
				components,
				taxable,
				taxAmount);
	}

	public TaxClassResolution resolveTaxClass(UUID itemId, UUID levelId, LocalDate asOfDate) {
		LocalDate d = asOfDate == null ? LocalDate.now() : asOfDate;
		String sql = """
				WITH RECURSIVE level_path AS (
				  SELECT l.level_id, 0 AS depth
				  FROM locations.levels l
				  WHERE l.level_id = COALESCE(
				    (SELECT x.level_id FROM locations.levels x WHERE x.reference_entity_id = :levelId LIMIT 1),
				    :levelId
				  )
				  UNION ALL
				  SELECT par.level_id, lp.depth + 1
				  FROM level_path lp
				  JOIN locations.levels cur ON cur.level_id = lp.level_id
				  JOIN locations.levels par ON par.level_id = cur.parent_level_id
				),
				item AS (
				  SELECT i.item_id, i.application_id, i.item_group_id, i.item_category_id
				  FROM items.item i
				  WHERE i.item_id = :itemId
				  LIMIT 1
				),
				cta AS (
				  SELECT cta.catalog_tax_assignment_id, cta.tax_group_id, tg.is_tax_inclusive
				  FROM item i
				  JOIN finance.catalog_tax_assignment cta
				    ON cta.application_id = i.application_id
				   AND cta.item_group_id = i.item_group_id
				   AND (cta.item_category_id IS NULL OR cta.item_category_id = i.item_category_id)
				   AND (cta.level_id IS NULL OR cta.level_id IN (SELECT level_id FROM level_path))
				   AND COALESCE(cta.is_active, true) = true
				   AND (cta.valid_from IS NULL OR cta.valid_from <= :asOf)
				   AND (cta.valid_to IS NULL OR cta.valid_to >= :asOf)
				  JOIN finance.tax_group tg ON tg.tax_group_id = cta.tax_group_id
				  ORDER BY
				    CASE WHEN cta.item_category_id IS NOT NULL THEN 0 ELSE 1 END,
				    CASE WHEN cta.level_id IS NOT NULL THEN 0 ELSE 1 END,
				    COALESCE((SELECT lp.depth FROM level_path lp WHERE lp.level_id = cta.level_id), 2147483647) ASC,
				    cta.priority ASC
				  LIMIT 1
				),
				chosen_rate AS (
				  SELECT tr.tax_rate_id, tr.level_id AS matched_level_id, lp.depth
				  FROM finance.tax_rate tr
				  JOIN level_path lp ON lp.level_id = tr.level_id
				  WHERE tr.tax_group_id = (SELECT tax_group_id FROM cta)
				    AND COALESCE(tr.is_active, true) = true
				    AND (tr.start_date IS NULL OR tr.start_date <= :asOf)
				    AND (tr.end_date IS NULL OR tr.end_date >= :asOf)
				  ORDER BY lp.depth ASC, tr.start_date DESC NULLS LAST
				  LIMIT 1
				)
				SELECT
				  (SELECT tax_group_id FROM cta) AS tax_group_id,
				  (SELECT catalog_tax_assignment_id FROM cta) AS catalog_tax_assignment_id,
				  COALESCE((SELECT is_tax_inclusive FROM cta), false) AS is_tax_inclusive,
				  CASE WHEN EXISTS (SELECT 1 FROM cta) THEN 'CATALOG_ASSIGNMENT'
				       ELSE 'NO_RATE' END AS reason,
				  (SELECT matched_level_id FROM chosen_rate) AS matched_level_id,
				  (SELECT tax_rate_id FROM chosen_rate) AS tax_rate_id
				""";

		MapSqlParameterSource ps = new MapSqlParameterSource()
				.addValue("itemId", itemId)
				.addValue("levelId", levelId)
				.addValue("asOf", d);

		List<Map<String, Object>> rows = jdbc.queryForList(sql, ps);
		if (rows.isEmpty()) {
			return new TaxClassResolution(null, null, null, false, TaxDeterminationResult.Reason.NO_RATE, List.of(), null);
		}
		Map<String, Object> row = rows.get(0);
		UUID taxGroupId = (UUID) row.get("tax_group_id");
		UUID assignmentId = (UUID) row.get("catalog_tax_assignment_id");
		UUID matchedLevelId = (UUID) row.get("matched_level_id");
		UUID taxRateId = (UUID) row.get("tax_rate_id");
		boolean inclusive = Boolean.TRUE.equals(row.get("is_tax_inclusive"));
		String reasonCode = String.valueOf(row.get("reason"));
		TaxDeterminationResult.Reason reason = switch (reasonCode) {
			case "CATALOG_ASSIGNMENT" -> TaxDeterminationResult.Reason.CATALOG_ASSIGNMENT;
			case "ITEM_TAX_GROUP_FALLBACK" -> TaxDeterminationResult.Reason.ITEM_TAX_GROUP_FALLBACK;
			default -> TaxDeterminationResult.Reason.NO_RATE;
		};

		List<TaxRateAllocationDTO> allocs = List.of();
		if (taxRateId != null) {
			allocs = loadAllocations(taxRateId);
		} else if (taxGroupId != null) {
			allocs = transactionDAO.getTaxRatesByGroupAndLevel(taxGroupId, levelId);
		}

		return new TaxClassResolution(taxGroupId, assignmentId, matchedLevelId, inclusive, reason, allocs, null);
	}

	private List<TaxRateAllocationDTO> loadAllocations(UUID taxRateId) {
		String sql = """
				SELECT tra.tax_rate_id, tra.tax_rate_allocation_id, tra.tax_rate_percentage
				FROM finance.tax_rate_allocation tra
				WHERE tra.tax_rate_id = :taxRateId
				  AND COALESCE(tra.is_active, true) = true
				ORDER BY tra.tax_rate_allocation_id
				""";
		return jdbc.query(sql, Map.of("taxRateId", taxRateId), (rs, i) -> {
			TaxRateAllocationDTO dto = new TaxRateAllocationDTO();
			dto.setTaxRateId((UUID) rs.getObject("tax_rate_id"));
			dto.setTaxRateAllocationId((UUID) rs.getObject("tax_rate_allocation_id"));
			dto.setTaxRatePercentage(rs.getBigDecimal("tax_rate_percentage"));
			return dto;
		});
	}

	private UUID resolveClientExemptId(UUID clientRoleId) {
		if (clientRoleId == null) return null;
		String directSql = """
				SELECT tax_exempt_id
				FROM clients.client_role
				WHERE client_role_id = :id
				LIMIT 1
				""";
		List<UUID> direct = jdbc.query(directSql, Map.of("id", clientRoleId),
				(rs, i) -> (UUID) rs.getObject("tax_exempt_id"));
		if (!direct.isEmpty() && direct.get(0) != null) {
			return direct.get(0);
		}

		// Bridge CRM characteristic "Tax Exempt" = Yes → finance.tax_exempt category.
		String charSql = """
				SELECT 1
				FROM clients.client_characteristic cc
				JOIN clients.client_characteristic_type cct
				  ON cct.client_characteristic_type_id = cc.client_characteristic_type_id
				LEFT JOIN clients.client_characteristic_values ccv
				  ON ccv.client_characteristic_values_id = cc.client_characteristic_values_id
				WHERE cc.client_role_id = :id
				  AND lower(cct.name) = 'tax exempt'
				  AND lower(COALESCE(ccv.value, cc.characteristic, '')) = 'yes'
				LIMIT 1
				""";
		List<Integer> yes = jdbc.query(charSql, Map.of("id", clientRoleId), (rs, i) -> 1);
		if (yes.isEmpty()) {
			return null;
		}

		UUID appId = null;
		try {
			appId = io.clubone.transaction.security.AccessContext.applicationId();
		} catch (Exception ignore) {
		}
		MapSqlParameterSource ps = new MapSqlParameterSource();
		StringBuilder teSql = new StringBuilder("""
				SELECT te.tax_exempt_id
				FROM finance.tax_exempt te
				WHERE COALESCE(te.is_active, true) = true
				  AND lower(te.name) LIKE '%exempt%'
				""");
		if (appId != null) {
			teSql.append(" AND te.application_id = :appId");
			ps.addValue("appId", appId);
		}
		teSql.append(" ORDER BY te.name LIMIT 1");
		List<UUID> ids = jdbc.query(teSql.toString(), ps, (rs, i) -> (UUID) rs.getObject("tax_exempt_id"));
		return ids.isEmpty() ? null : ids.get(0);
	}

	private boolean isExemptAtLevel(UUID taxExemptId, UUID levelId) {
		String sql = """
				WITH RECURSIVE level_path AS (
				  SELECT l.level_id, 0 AS depth
				  FROM locations.levels l
				  WHERE l.level_id = :levelId
				  UNION ALL
				  SELECT par.level_id, lp.depth + 1
				  FROM level_path lp
				  JOIN locations.levels cur ON cur.level_id = lp.level_id
				  JOIN locations.levels par ON par.level_id = cur.parent_level_id
				)
				SELECT 1
				FROM finance.tax_exempt_level tel
				WHERE tel.tax_exempt_id = :exemptId
				  AND COALESCE(tel.is_active, true) = true
				  AND tel.level_id IN (SELECT level_id FROM level_path)
				LIMIT 1
				""";
		List<Integer> hit = jdbc.query(sql,
				new MapSqlParameterSource().addValue("exemptId", taxExemptId).addValue("levelId", levelId),
				(rs, i) -> 1);
		return !hit.isEmpty();
	}
}
