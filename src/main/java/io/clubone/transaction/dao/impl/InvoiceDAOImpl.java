package io.clubone.transaction.dao.impl;

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.BeanPropertyRowMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import io.clubone.transaction.dao.EntityLookupDao;
import io.clubone.transaction.dao.InvoiceDAO;
import io.clubone.transaction.v2.vo.EntityLevelInfoDTO;
import io.clubone.transaction.v2.vo.InvoiceEntityDiscountDTO;
import io.clubone.transaction.vo.InvoiceDTO;
import io.clubone.transaction.vo.InvoiceEntityDTO;
import io.clubone.transaction.vo.InvoiceEntityTaxDTO;
import io.clubone.transaction.vo.TransactionDTO;

@Service
public class InvoiceDAOImpl implements InvoiceDAO {

	@Autowired
	@Qualifier("cluboneJdbcTemplate")
	private JdbcTemplate cluboneJdbcTemplate;

	@Autowired
	private EntityLookupDao entityLookupDao;

	@Override
	public InvoiceDTO findResolvedById(UUID invoiceId) {
		// 1) Invoice header
		final String invSql = """
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
				      COALESCE(i.is_paid,false) AS isPaid,
				      i.level_id            AS levelId,
				      i.created_by          AS createdBy,
				      lis.status_name as invoiceStatus
				    FROM "transaction".invoice i
				    join "transaction".lu_invoice_status lis on lis.invoice_status_id =i.invoice_status_id
				    WHERE i.invoice_id = ?
				""";

		List<InvoiceDTO> list = cluboneJdbcTemplate.query(invSql, BeanPropertyRowMapper.newInstance(InvoiceDTO.class),
				invoiceId);
		if (list.isEmpty())
			return null;

		InvoiceDTO invoice = list.get(0);

		// 2) Invoice entities (line items)
		final String liSql = """
				    SELECT
				      ie.invoice_entity_id       AS invoiceEntityId,
				      ie.entity_type_id          AS entityTypeId,
				      ie.entity_id               AS entityId,
				      ie.price_plan_template_id  AS pricePlanTemplateId,
				      ie.entity_description      AS entityDescription,
				      CAST(ie.contract_start_date AS date) AS contractStartDate,
				      COALESCE(ie.quantity,0)    AS quantity,
				      ie.unit_price              AS unitPrice,
				      ie.discount_amount         AS discountAmount,
				      ie.tax_amount              AS taxAmount,
				      ie.total_amount            AS totalAmount,
				      ie.parent_invoice_entity_id AS parentInvoiceEntityId
				    FROM "transaction".invoice_entity ie
				    WHERE ie.invoice_id = ?
				    ORDER BY ie.created_on ASC
				""";

		List<InvoiceEntityDTO> lines = cluboneJdbcTemplate.query(liSql,
				BeanPropertyRowMapper.newInstance(InvoiceEntityDTO.class), invoiceId);

		if (!lines.isEmpty()) {
			// Batch-load discounts and taxes for all entities (avoid N+1)
			Map<UUID, List<InvoiceEntityDiscountDTO>> discounts = fetchDiscountsFor(lines);
			Map<UUID, List<InvoiceEntityTaxDTO>> taxes = fetchTaxesFor(lines);

			for (InvoiceEntityDTO li : lines) {
				Optional<EntityLevelInfoDTO> enityDetail = entityLookupDao.resolveEntityAndLevel(li.getEntityTypeId(),
						li.getEntityId(), invoice.getLevelId());
				if (enityDetail.isPresent()) {
					li.setEntityName(enityDetail.get().entityName());
				}
				li.setDiscounts(discounts.getOrDefault(li.getInvoiceEntityId(), List.of()));
				li.setTaxes(taxes.getOrDefault(li.getInvoiceEntityId(), List.of()));
			}
		}

		invoice.setLineItems(lines);
		return invoice;
	}

	@Override
	public TransactionDTO findLatestTransactionByInvoiceId(UUID invoiceId) {
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
				    FROM "transaction"."transaction" t
				    JOIN "transaction".invoice i ON i.invoice_id = t.invoice_id
				    WHERE t.invoice_id = ?
				    ORDER BY t.transaction_date DESC NULLS LAST, t.created_on DESC
				    LIMIT 1
				""";

		List<TransactionDTO> txns = cluboneJdbcTemplate.query(txnSql, (rs, n) -> {
			TransactionDTO dto = new TransactionDTO();
			dto.setTransactionId(UUID.fromString(rs.getString("transactionId")));
			dto.setClientPaymentTransactionId(rs.getString("clientPaymentTransactionId") == null ? null
					: UUID.fromString(rs.getString("clientPaymentTransactionId")));
			dto.setClientAgreementId(rs.getString("clientAgreementId") == null ? null
					: UUID.fromString(rs.getString("clientAgreementId")));
			dto.setLevelId(rs.getString("levelId") == null ? null : UUID.fromString(rs.getString("levelId")));
			dto.setInvoiceId(UUID.fromString(rs.getString("invoiceId")));
			dto.setTransactionCode(rs.getString("transactionCode"));
			Timestamp ts = rs.getTimestamp("transactionDate");
			dto.setTransactionDate(ts);
			dto.setCreatedBy(rs.getString("createdBy") == null ? null : UUID.fromString(rs.getString("createdBy")));
			return dto;
		}, invoiceId);
		return txns.isEmpty() ? null : txns.get(0);
	}

	private Map<UUID, List<InvoiceEntityDiscountDTO>> fetchDiscountsFor(List<InvoiceEntityDTO> lines) {
		List<UUID> ids = lines.stream().map(io.clubone.transaction.vo.InvoiceEntityDTO::getInvoiceEntityId).toList();
		if (ids.isEmpty())
			return Map.of();

		String in = ids.stream().map(id -> "?").collect(java.util.stream.Collectors.joining(","));

		final String sql = """
				     SELECT
				      ied.invoice_entity_id,
				      ied.discount_id,
				      /* we don't store a percent/rate in invoice_entity_discount; return NULL */
				      NULL::numeric                    AS discount_rate,
				      ied.discount_amount,
				      ied.calculation_type_id,
				      ied.adjustment_type_id,
				      lct.name as calculationType
				    FROM "transaction".invoice_entity_discount ied
				    join discount.lu_calculation_type lct on lct.calculation_type_id =ied.calculation_type_id
				    WHERE ied.invoice_entity_id IN (%s)
				    ORDER BY ied.created_on ASC
				""".formatted(in);

		Map<UUID, List<io.clubone.transaction.v2.vo.InvoiceEntityDiscountDTO>> map = new HashMap<>();
		cluboneJdbcTemplate.query(sql, rs -> {
			UUID invEntId = UUID.fromString(rs.getString("invoice_entity_id"));
			var dto = new io.clubone.transaction.v2.vo.InvoiceEntityDiscountDTO();
			dto.setDiscountId(UUID.fromString(rs.getString("discount_id")));
			dto.setDiscountRate(rs.getBigDecimal("discount_rate")); // will be null
			dto.setDiscountAmount(rs.getBigDecimal("discount_amount"));
			dto.setCalculationTypeId(rs.getString("calculation_type_id") == null ? null
					: UUID.fromString(rs.getString("calculation_type_id")));
			dto.setAdjustmentTypeId(rs.getString("adjustment_type_id") == null ? null
					: UUID.fromString(rs.getString("adjustment_type_id")));
			dto.setCalculationType(rs.getString("calculationType"));
			map.computeIfAbsent(invEntId, k -> new ArrayList<>()).add(dto);
		}, ids.toArray());

		return map;
	}

	private Map<UUID, List<InvoiceEntityTaxDTO>> fetchTaxesFor(List<InvoiceEntityDTO> lines) {
		List<UUID> ids = lines.stream().map(io.clubone.transaction.vo.InvoiceEntityDTO::getInvoiceEntityId).toList();
		if (ids.isEmpty())
			return Map.of();

		String in = ids.stream().map(id -> "?").collect(java.util.stream.Collectors.joining(","));

		final String sql = """
								    SELECT DISTINCT
				    iet.invoice_entity_id,
				    iet.tax_rate_id,
				    iet.tax_rate,
				    iet.tax_amount,
				    ta."name" AS taxAuthority,
				    iet.created_on
				FROM "transaction".invoice_entity_tax iet
				JOIN finance.tax_rate_allocation tra
				    ON tra.tax_rate_id = iet.tax_rate_id
				JOIN finance.tax_authority ta
				    ON ta.tax_authority_id = tra.tax_authority_id
				WHERE iet.invoice_entity_id IN (%s)
				ORDER BY iet.created_on ASC;

								""".formatted(in);

		Map<UUID, List<io.clubone.transaction.vo.InvoiceEntityTaxDTO>> map = new HashMap<>();
		cluboneJdbcTemplate.query(sql, rs -> {
			UUID invEntId = UUID.fromString(rs.getString("invoice_entity_id"));
			var dto = new io.clubone.transaction.vo.InvoiceEntityTaxDTO();
			dto.setTaxRateId(rs.getString("tax_rate_id") == null ? null : UUID.fromString(rs.getString("tax_rate_id")));
			dto.setTaxRate(rs.getBigDecimal("tax_rate"));
			dto.setTaxAmount(rs.getBigDecimal("tax_amount"));
			dto.setTaxAuthority(rs.getString("taxAuthority"));

			map.computeIfAbsent(invEntId, k -> new ArrayList<>()).add(dto);
		}, ids.toArray());

		return map;
	}
	
	 @Override
	    public int updateClientAgreementId(UUID invoiceId, UUID clientAgreementId) {
	        String sql = """
	            UPDATE "transaction".invoice
	            SET client_agreement_id = ?
	            WHERE invoice_id = ?
	        """;
	        return cluboneJdbcTemplate.update(sql, clientAgreementId, invoiceId);
	    }

}
