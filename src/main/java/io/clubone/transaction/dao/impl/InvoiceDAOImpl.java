package io.clubone.transaction.dao.impl;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.BeanPropertyRowMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import io.clubone.transaction.dao.EntityLookupDao;
import io.clubone.transaction.dao.InvoiceDAO;
import io.clubone.transaction.dao.InvoiceEntityPromotionDAO;
import io.clubone.transaction.dao.TransactionDAO;
import io.clubone.transaction.dao.InvoiceEntityPromotionDAO.InvoiceEntityPromotionRow;
import io.clubone.transaction.v2.vo.EntityLevelInfoDTO;
import io.clubone.transaction.v2.vo.InvoiceEntityPriceBandDTO;
import io.clubone.transaction.v2.vo.InvoiceEntityPromotionDTO;
import io.clubone.transaction.vo.InvoiceDTO;
import io.clubone.transaction.vo.InvoiceEntityDTO;
import io.clubone.transaction.vo.InvoiceEntityTaxDTO;
import io.clubone.transaction.vo.TaxRateAllocationDTO;
import io.clubone.transaction.vo.TransactionDTO;

@Service
public class InvoiceDAOImpl implements InvoiceDAO {

	@Autowired
	@Qualifier("cluboneJdbcTemplate")
	private JdbcTemplate cluboneJdbcTemplate;

	@Autowired
	private EntityLookupDao entityLookupDao;

	@Autowired
	private InvoiceEntityPromotionDAO invoiceEntityPromotionDAO;

	@Autowired
	private TransactionDAO transactionDAO;

	private static final String INV_HEADER_SQL = """
			SELECT
			  i.invoice_id          AS invoiceId,
			  i.invoice_number      AS invoiceNumber,
			  i.invoice_date        AS invoiceDate,
			  i.client_role_id      AS clientRoleId,
			  i.billing_address     AS billingAddress,
			  i.invoice_status_id   AS invoiceStatusId,
			  i.total_amount        AS totalAmount,
			  i.sub_total           AS subTotal,
			  i.tax_amount          AS taxAmount,
			  i.discount_amount     AS discountAmount,
			  COALESCE(i.is_paid, false) AS isPaid,
			  i.level_id            AS levelId,
			  i.created_by          AS createdBy,
			  i.client_agreement_id AS clientAgreementId,
			  i.billing_run_id      AS billingRunId,
			  i.billing_collection_type_id AS billingCollectionTypeId,
			  lbct.code             AS billingCollectionTypeCode,
			  lbct."name"           AS billingCollectionTypeName,
			  lis.status_name       AS invoiceStatus
			FROM "transactions".invoice i
			JOIN "transactions".lu_invoice_status lis ON lis.invoice_status_id = i.invoice_status_id
			LEFT JOIN "transactions".lu_billing_collection_type lbct
			  ON lbct.billing_collection_type_id = i.billing_collection_type_id
			 AND COALESCE(lbct.is_active, true) = true
			WHERE i.invoice_id = ?
			  AND COALESCE(i.is_active, true) = true
			""";

	private static final String LINE_ITEMS_BASIC_SQL = """
			SELECT
			  ie.invoice_entity_id        AS invoiceEntityId,
			  ie.entity_type_id           AS entityTypeId,
			  ie.entity_id                AS entityId,
			  ie.price_plan_template_id   AS pricePlanTemplateId,
			  ie.entity_description       AS entityDescription,
			  COALESCE(CAST(ie.service_period_start AS date), CAST(ie.created_on AS date)) AS contractStartDate,
			  COALESCE(ie.quantity, 0)    AS quantity,
			  ie.unit_price               AS unitPrice,
			  ie.discount_amount          AS discountAmount,
			  ie.tax_amount               AS taxAmount,
			  ie.total_amount             AS totalAmount,
			  ie.parent_invoice_entity_id AS parentInvoiceEntityId
			FROM "transactions".invoice_entity ie
			WHERE ie.invoice_id = ?
			  AND COALESCE(ie.is_active, true) = true
			ORDER BY ie.created_on ASC
			""";

	private static final String LINE_ITEMS_FULL_SQL = """
			SELECT
			  ie.invoice_entity_id        AS invoiceEntityId,
			  ie.entity_type_id           AS entityTypeId,
			  ie.entity_id                AS entityId,
			  ie.price_plan_template_id   AS pricePlanTemplateId,
			  ie.entity_description       AS entityDescription,
			  COALESCE(CAST(ie.service_period_start AS date), CAST(ie.created_on AS date)) AS contractStartDate,
			  COALESCE(ie.quantity, 0)    AS quantity,
			  ie.unit_price               AS unitPrice,
			  ie.discount_amount          AS discountAmount,
			  ie.tax_amount               AS taxAmount,
			  ie.total_amount             AS totalAmount,
			  ie.parent_invoice_entity_id AS parentInvoiceEntityId,
			  ie.client_agreement_id      AS clientAgreementId,
			  ie.billing_schedule_id      AS billingScheduleId,
			  ie.subscription_instance_id AS subscriptionInstanceId,
			  ie.cycle_number             AS cycleNumber,
			  CAST(ie.service_period_start AS date) AS servicePeriodStart,
			  CAST(ie.service_period_end AS date)   AS servicePeriodEnd,
			  ie.charge_line_kind_id      AS chargeLineKindId,
			  lclk.code                   AS chargeLineKindCode,
			  lclk."name"                 AS chargeLineKindName,
			  ie.entity_version_id        AS entityVersionId
			FROM "transactions".invoice_entity ie
			LEFT JOIN "transactions".lu_charge_line_kind lclk
			  ON lclk.charge_line_kind_id = ie.charge_line_kind_id
			 AND COALESCE(lclk.is_active, true) = true
			WHERE ie.invoice_id = ?
			  AND COALESCE(ie.is_active, true) = true
			ORDER BY ie.created_on ASC
			""";

	@Override
	public InvoiceDTO findResolvedById(UUID invoiceId) {
		List<InvoiceDTO> list = cluboneJdbcTemplate.query(INV_HEADER_SQL,
				BeanPropertyRowMapper.newInstance(InvoiceDTO.class), invoiceId);
		if (list.isEmpty()) {
			return null;
		}
		InvoiceDTO invoice = list.get(0);
		List<InvoiceEntityDTO> lines = cluboneJdbcTemplate.query(LINE_ITEMS_BASIC_SQL,
				BeanPropertyRowMapper.newInstance(InvoiceEntityDTO.class), invoiceId);
		enrichLineItemsBasic(invoice, lines);
		invoice.setLineItems(lines);
		return invoice;
	}

	@Override
	public InvoiceDTO findResolvedFullById(UUID invoiceId) {
		List<InvoiceDTO> list = cluboneJdbcTemplate.query(INV_HEADER_SQL,
				BeanPropertyRowMapper.newInstance(InvoiceDTO.class), invoiceId);
		if (list.isEmpty()) {
			return null;
		}
		InvoiceDTO invoice = list.get(0);
		List<InvoiceEntityDTO> lines = cluboneJdbcTemplate.query(LINE_ITEMS_FULL_SQL,
				BeanPropertyRowMapper.newInstance(InvoiceEntityDTO.class), invoiceId);
		enrichLineItemsFull(invoice, lines);
		invoice.setLineItems(lines);
		return invoice;
	}

	@Override
	public Optional<UUID> findInvoiceIdByNumber(String invoiceNumber) {
		if (invoiceNumber == null || invoiceNumber.isBlank()) {
			return Optional.empty();
		}
		try {
			UUID id = cluboneJdbcTemplate.queryForObject("""
					SELECT invoice_id
					FROM "transactions".invoice
					WHERE invoice_number = ?
					  AND COALESCE(is_active, true) = true
					LIMIT 1
					""", UUID.class, invoiceNumber.trim());
			return Optional.ofNullable(id);
		} catch (EmptyResultDataAccessException e) {
			return Optional.empty();
		}
	}

	private void enrichLineItemsBasic(InvoiceDTO invoice, List<InvoiceEntityDTO> lines) {
		if (lines == null || lines.isEmpty()) {
			return;
		}
		Map<UUID, List<InvoiceEntityTaxDTO>> taxes = fetchTaxesFor(lines);
		for (InvoiceEntityDTO li : lines) {
			resolveEntityName(invoice, li);
			li.setTaxes(mergeAndEnrichTaxes(invoice, li,
					taxes.getOrDefault(li.getInvoiceEntityId(), List.of())));
		}
	}

	private void enrichLineItemsFull(InvoiceDTO invoice, List<InvoiceEntityDTO> lines) {
		if (lines == null || lines.isEmpty()) {
			return;
		}
		Map<UUID, List<InvoiceEntityTaxDTO>> taxes = fetchTaxesFor(lines);
		Map<UUID, List<InvoiceEntityPriceBandDTO>> bands = fetchPriceBandsFor(lines);
		List<UUID> ids = lines.stream().map(InvoiceEntityDTO::getInvoiceEntityId).toList();
		Map<UUID, List<InvoiceEntityPromotionRow>> promos = invoiceEntityPromotionDAO
				.fetchActivePromotionsByInvoiceEntityIds(ids);

		for (InvoiceEntityDTO li : lines) {
			resolveEntityName(invoice, li);
			li.setTaxes(mergeAndEnrichTaxes(invoice, li,
					taxes.getOrDefault(li.getInvoiceEntityId(), List.of())));
			li.setDiscounts(Collections.emptyList());
			li.setPriceBands(bands.getOrDefault(li.getInvoiceEntityId(), List.of()));
			li.setPromotions(toPromotionDtos(promos.getOrDefault(li.getInvoiceEntityId(), List.of())));
		}
	}

	/**
	 * Finance-backed lines have rows in {@code invoice_entity_tax}. POS/client-only tax is stored on
	 * {@link InvoiceEntityDTO#getTaxAmount()} only (no FKs), so the DB query returns nothing — we
	 * synthesize one summary row for read APIs, then {@link #enrichSyntheticTaxFromFinance} fills
	 * IDs when the rate matches finance config.
	 */
	private static List<InvoiceEntityTaxDTO> taxesFromDbOrLineFallback(List<InvoiceEntityTaxDTO> fromDb,
			InvoiceEntityDTO li) {
		if (fromDb != null && !fromDb.isEmpty()) {
			return fromDb;
		}
		if (li.getTaxAmount() == null || li.getTaxAmount().compareTo(BigDecimal.ZERO) <= 0) {
			return List.of();
		}
		InvoiceEntityTaxDTO t = new InvoiceEntityTaxDTO();
		t.setTaxAmount(li.getTaxAmount());
		BigDecimal unit = li.getUnitPrice() != null ? li.getUnitPrice() : BigDecimal.ZERO;
		int q = Math.max(1, li.getQuantity());
		BigDecimal disc = li.getDiscountAmount() != null ? li.getDiscountAmount() : BigDecimal.ZERO;
		BigDecimal net = unit.multiply(BigDecimal.valueOf(q)).subtract(disc);
		if (net.compareTo(BigDecimal.ZERO) > 0) {
			t.setTaxRate(li.getTaxAmount().multiply(new BigDecimal("100")).divide(net, 4, RoundingMode.HALF_UP));
		}
		return List.of(t);
	}

	private List<InvoiceEntityTaxDTO> mergeAndEnrichTaxes(InvoiceDTO invoice, InvoiceEntityDTO li,
			List<InvoiceEntityTaxDTO> fromDb) {
		List<InvoiceEntityTaxDTO> merged = taxesFromDbOrLineFallback(fromDb, li);
		for (InvoiceEntityTaxDTO tx : merged) {
			enrichSyntheticTaxFromFinance(invoice, li, tx);
		}
		return merged;
	}

	/**
	 * POS create does not persist {@code invoice_entity_tax} without finance FKs, so synthetic rows
	 * have null IDs until we match {@code taxRate} to {@link TaxRateAllocationDTO} for item+level.
	 */
	private void enrichSyntheticTaxFromFinance(InvoiceDTO invoice, InvoiceEntityDTO li, InvoiceEntityTaxDTO t) {
		if (t == null) {
			return;
		}
		if (t.getTaxRateAllocationId() != null && t.getTaxAuthority() == null) {
			t.setTaxAuthority(lookupTaxAuthorityName(t.getTaxRateAllocationId()));
			return;
		}
		if (t.getTaxRateId() != null) {
			return;
		}
		if (invoice.getLevelId() == null || li.getEntityId() == null || t.getTaxRate() == null) {
			return;
		}
		try {
			UUID taxGroupId = transactionDAO.findTaxGroupIdForItem(li.getEntityId(), invoice.getLevelId());
			if (taxGroupId == null) {
				return;
			}
			List<TaxRateAllocationDTO> allocs = transactionDAO.getTaxRatesByGroupAndLevel(taxGroupId,
					invoice.getLevelId());
			TaxRateAllocationDTO match = matchTaxAllocationByPercentage(allocs, t.getTaxRate());
			if (match != null) {
				t.setTaxRateId(match.getTaxRateId());
				t.setTaxRateAllocationId(match.getTaxRateAllocationId());
				t.setTaxRate(match.getTaxRatePercentage());
				t.setTaxAuthority(lookupTaxAuthorityName(match.getTaxRateAllocationId()));
			}
		} catch (Exception ignored) {
			// leave synthetic row as amount + effective % only
		}
	}

	private String lookupTaxAuthorityName(UUID taxRateAllocationId) {
		if (taxRateAllocationId == null) {
			return null;
		}
		try {
			return cluboneJdbcTemplate.queryForObject("""
					SELECT ta."name"
					FROM finance.tax_rate_allocation tra
					JOIN finance.tax_authority ta ON ta.tax_authority_id = tra.tax_authority_id
					WHERE tra.tax_rate_allocation_id = ?
					LIMIT 1
					""", String.class, taxRateAllocationId);
		} catch (EmptyResultDataAccessException e) {
			return null;
		}
	}

	private static TaxRateAllocationDTO matchTaxAllocationByPercentage(List<TaxRateAllocationDTO> allocs,
			BigDecimal effectivePct) {
		if (allocs == null || allocs.isEmpty() || effectivePct == null) {
			return null;
		}
		TaxRateAllocationDTO best = null;
		BigDecimal bestDiff = null;
		for (TaxRateAllocationDTO a : allocs) {
			if (a.getTaxRatePercentage() == null) {
				continue;
			}
			BigDecimal diff = a.getTaxRatePercentage().subtract(effectivePct).abs();
			if (diff.compareTo(new BigDecimal("0.25")) <= 0) {
				if (best == null || bestDiff == null || diff.compareTo(bestDiff) < 0) {
					best = a;
					bestDiff = diff;
				}
			}
		}
		return best;
	}

	private void resolveEntityName(InvoiceDTO invoice, InvoiceEntityDTO li) {
		Optional<EntityLevelInfoDTO> enityDetail = Optional.empty();
		try {
			enityDetail = entityLookupDao.resolveEntityAndLevel(li.getEntityTypeId(), li.getEntityId(),
					invoice.getLevelId());
		} catch (Exception ignored) {
		}
		if (enityDetail.isPresent()) {
			li.setEntityName(enityDetail.get().entityName());
		}
	}

	private static List<InvoiceEntityPromotionDTO> toPromotionDtos(List<InvoiceEntityPromotionRow> rows) {
		if (rows == null || rows.isEmpty()) {
			return List.of();
		}
		List<InvoiceEntityPromotionDTO> out = new ArrayList<>();
		for (InvoiceEntityPromotionRow r : rows) {
			InvoiceEntityPromotionDTO d = new InvoiceEntityPromotionDTO();
			d.setPromotionVersionId(r.promotionVersionId());
			d.setPromotionApplicabilityId(r.promotionApplicabilityId());
			d.setPromotionEffectId(r.promotionEffectId());
			d.setPromotionAmount(r.promotionAmount());
			out.add(d);
		}
		return out;
	}

	private Map<UUID, List<InvoiceEntityPriceBandDTO>> fetchPriceBandsFor(List<InvoiceEntityDTO> lines) {
		List<UUID> ids = lines.stream().map(InvoiceEntityDTO::getInvoiceEntityId).toList();
		if (ids.isEmpty()) {
			return Map.of();
		}
		String in = ids.stream().map(id -> "?").collect(Collectors.joining(","));
		final String sql = """
				SELECT invoice_entity_id, price_cycle_band_id, unit_price, COALESCE(is_price_overridden, false) AS is_price_overridden
				FROM "transactions".invoice_entity_price_band
				WHERE invoice_entity_id IN (%s)
				  AND COALESCE(is_active, true) = true
				ORDER BY created_on ASC
				""".formatted(in);
		Map<UUID, List<InvoiceEntityPriceBandDTO>> map = new HashMap<>();
		cluboneJdbcTemplate.query(sql, rs -> {
			UUID invEntId = rs.getObject("invoice_entity_id", UUID.class);
			InvoiceEntityPriceBandDTO dto = new InvoiceEntityPriceBandDTO();
			dto.setPriceCycleBandId(rs.getObject("price_cycle_band_id", UUID.class));
			dto.setUnitPrice(rs.getBigDecimal("unit_price"));
			dto.setIsPriceOverridden(rs.getBoolean("is_price_overridden"));
			map.computeIfAbsent(invEntId, k -> new ArrayList<>()).add(dto);
		}, ids.toArray());
		return map;
	}

	@Override
	public TransactionDTO findLatestTransactionByInvoiceId(UUID invoiceId) {
		List<TransactionDTO> txns = findAllTransactionsByInvoiceId(invoiceId);
		return txns.isEmpty() ? null : txns.get(0);
	}

	@Override
	public List<TransactionDTO> findAllTransactionsByInvoiceId(UUID invoiceId) {
		final String txnSql = """
				SELECT
				  t.transaction_id                 AS transactionId,
				  t.client_payment_transaction_id  AS clientPaymentTransactionId,
				  i.client_agreement_id            AS clientAgreementId,
				  i.level_id                       AS levelId,
				  t.invoice_id                     AS invoiceId,
				  t.transaction_number             AS transactionCode,
				  t.transaction_date               AS transactionDate,
				  t.created_by                     AS createdBy
				FROM "transactions"."transaction" t
				JOIN "transactions".invoice i ON i.invoice_id = t.invoice_id
				WHERE t.invoice_id = ?
				  AND COALESCE(t.is_active, true) = true
				ORDER BY t.transaction_date DESC NULLS LAST, t.created_on DESC
				""";

		return cluboneJdbcTemplate.query(txnSql, (rs, n) -> mapTransactionRow(rs), invoiceId);
	}

	private static TransactionDTO mapTransactionRow(ResultSet rs) throws SQLException {
		TransactionDTO dto = new TransactionDTO();
		dto.setTransactionId(rs.getObject("transactionId", UUID.class));
		dto.setClientPaymentTransactionId(rs.getObject("clientPaymentTransactionId", UUID.class));
		dto.setClientAgreementId(rs.getObject("clientAgreementId", UUID.class));
		dto.setLevelId(rs.getObject("levelId", UUID.class));
		dto.setInvoiceId(rs.getObject("invoiceId", UUID.class));
		dto.setTransactionCode(rs.getString("transactionCode"));
		Timestamp ts = rs.getTimestamp("transactionDate");
		dto.setTransactionDate(ts);
		dto.setSubscriptionBillingHistoryId(rs.getObject("subscriptionBillingHistoryId", UUID.class));
		dto.setCreatedBy(rs.getObject("createdBy", UUID.class));
		return dto;
	}

	private Map<UUID, List<InvoiceEntityTaxDTO>> fetchTaxesFor(List<InvoiceEntityDTO> lines) {
		List<UUID> ids = lines.stream().map(io.clubone.transaction.vo.InvoiceEntityDTO::getInvoiceEntityId).toList();
		if (ids.isEmpty())
			return Map.of();

		String in = ids.stream().map(id -> "?").collect(java.util.stream.Collectors.joining(","));

		final String sql = """
				SELECT
				    iet.invoice_entity_id,
				    iet.tax_rate_id,
				    iet.tax_rate_percentage,
				    iet.tax_amount,
				    iet.tax_rate_allocation_id,
				    ta."name" AS taxAuthority,
				    iet.created_on
				FROM "transactions".invoice_entity_tax iet
				JOIN finance.tax_rate_allocation tra
				    ON tra.tax_rate_allocation_id = iet.tax_rate_allocation_id
				JOIN finance.tax_authority ta
				    ON ta.tax_authority_id = tra.tax_authority_id
				WHERE iet.invoice_entity_id IN (%s)
				  AND COALESCE(iet.is_active, true) = true
				ORDER BY iet.created_on ASC
				""".formatted(in);

		Map<UUID, List<io.clubone.transaction.vo.InvoiceEntityTaxDTO>> map = new HashMap<>();
		cluboneJdbcTemplate.query(sql, rs -> {
			UUID invEntId = rs.getObject("invoice_entity_id", UUID.class);
			var dto = new io.clubone.transaction.vo.InvoiceEntityTaxDTO();
			dto.setTaxRateId(rs.getObject("tax_rate_id", UUID.class));
			dto.setTaxRate(rs.getBigDecimal("tax_rate_percentage"));
			dto.setTaxAmount(rs.getBigDecimal("tax_amount"));
			dto.setTaxRateAllocationId(rs.getObject("tax_rate_allocation_id", UUID.class));
			dto.setTaxAuthority(rs.getString("taxAuthority"));
			map.computeIfAbsent(invEntId, k -> new ArrayList<>()).add(dto);
		}, ids.toArray());

		return map;
	}
	
	 @Override
	    public int updateClientAgreementId(UUID invoiceId, UUID clientAgreementId) {
	        String sql = """
	            UPDATE "transactions".invoice
	            SET client_agreement_id = ?
	            WHERE invoice_id = ?
	        """;
	        return cluboneJdbcTemplate.update(sql, clientAgreementId, invoiceId);
	    }

}
