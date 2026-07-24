package io.clubone.transaction.dao.impl;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

import io.clubone.transaction.dao.TransactionDAO;
import io.clubone.transaction.security.AccessContext;
import io.clubone.transaction.util.FrequencyUnit;
import io.clubone.transaction.v2.vo.BundlePriceCycleBandDTO;
import io.clubone.transaction.v2.vo.CalculationMode;
import io.clubone.transaction.v2.vo.CycleBandRef;
import io.clubone.transaction.v2.vo.DiscountDetailDTO;
import io.clubone.transaction.v2.vo.InvoiceAdjustmentDetailDTO;
import io.clubone.transaction.v2.vo.InvoiceDetailDTO;
import io.clubone.transaction.v2.vo.InvoiceDetailRaw;
import io.clubone.transaction.v2.vo.InvoiceEntityPriceBandDTO;
import io.clubone.transaction.v2.vo.InvoiceEntityPromotionDTO;
import io.clubone.transaction.v2.vo.InvoiceRefundAllocationDTO;
import io.clubone.transaction.v2.vo.InvoiceRefundDetailDTO;
import io.clubone.transaction.v2.vo.InvoiceTransactionDetailDTO;
import io.clubone.transaction.v2.vo.PaymentTimelineItemDTO;
import io.clubone.transaction.v2.vo.PromotionEffectValueDTO;
import io.clubone.transaction.vo.BundleComponent;
import io.clubone.transaction.vo.BundleItemPriceDTO;
import io.clubone.transaction.vo.EntityTypeDTO;
import io.clubone.transaction.vo.InvoiceDTO;
import io.clubone.transaction.vo.InvoiceEntityDTO;
import io.clubone.transaction.vo.InvoiceEntityRow;
import io.clubone.transaction.vo.InvoiceEntityTaxDTO;
import io.clubone.transaction.vo.InvoiceFlatRow;
import io.clubone.transaction.vo.InvoiceSeedRow;
import io.clubone.transaction.vo.InvoiceSummaryDTO;
import io.clubone.transaction.vo.ItemPriceDTO;
import io.clubone.transaction.vo.TaxRateAllocationDTO;
import io.clubone.transaction.vo.TransactionDTO;
import io.clubone.transaction.vo.TransactionEntityDTO;
import io.clubone.transaction.vo.TransactionEntityTaxDTO;

@Repository
public class TransactionDAOImpl implements TransactionDAO {

	@Autowired
	@Qualifier("cluboneJdbcTemplate")
	private JdbcTemplate cluboneJdbcTemplate;

	private static final Logger logger = LoggerFactory.getLogger(TransactionDAOImpl.class);

	/** Lookup caches: expire after 1 hour (min TTL policy). */
	private static final Cache<String, UUID> INVOICE_STATUS_ID_CACHE = Caffeine.newBuilder()
			.maximumSize(256)
			.expireAfterWrite(1, TimeUnit.HOURS)
			.build();
	private static final Cache<String, UUID> ENTITY_TYPE_ID_BY_NAME_CACHE = Caffeine.newBuilder()
			.maximumSize(256)
			.expireAfterWrite(1, TimeUnit.HOURS)
			.build();
	private static final Cache<UUID, EntityTypeDTO> ENTITY_TYPE_BY_ID_CACHE = Caffeine.newBuilder()
			.maximumSize(256)
			.expireAfterWrite(1, TimeUnit.HOURS)
			.build();
	private static final Cache<String, UUID> ACTIVATE_LOOKUP_CACHE = Caffeine.newBuilder()
			.maximumSize(64)
			.expireAfterWrite(1, TimeUnit.HOURS)
			.build();

	private static final String ENTITY_TYPE_SQL = """
			SELECT entity_type
			FROM "transactions".lu_entity_type let
			WHERE let.entity_type_id = ?
			""";

	private static final String ITEM_PRICE_SQL = """
						WITH RECURSIVE ancestors AS (
			  SELECT l.level_id, l.parent_level_id, 0 AS depth
			  FROM location.levels l
			  WHERE l.level_id = ?
			  UNION ALL
			  SELECT p.level_id, p.parent_level_id, a.depth + 1
			  FROM location.levels p
			  JOIN ancestors a ON a.parent_level_id = p.level_id
			),
			level_candidates AS (
			  SELECT level_id, depth FROM ancestors
			  UNION ALL
			  SELECT NULL::uuid AS level_id, 100000 AS depth
			)
			SELECT
			  i.item_description,
			  eff_ip.price AS "itemPrice",
			  i.tax_group_id,
			  eff_ip.price_level_id
			FROM items.item i
			JOIN LATERAL (
			  SELECT ip.price,
			         ip.level_id AS price_level_id
			  FROM items.item_price ip
			  JOIN level_candidates lc
			    ON (ip.level_id = lc.level_id)
			    OR (ip.level_id IS NULL AND lc.level_id IS NULL)
			  WHERE i.item_id = ?
			  ORDER BY lc.depth ASC
			  LIMIT 1
			) eff_ip ON TRUE
			WHERE i.item_id = ?;
						""";

	private static final String INVOICE_SUMMARY_SQL = """
			SELECT
			    i.client_role_id,
			    i.total_amount,
			    i.level_id,
			    i.client_agreement_id
			FROM "transactions".invoice i
			WHERE i.invoice_id = ?
			  AND i.application_id = ?
			""";

	private static final String UPDATE_TRANSACTION_CLIENT_AGREEMENT_SQL = """
			UPDATE transactions.invoice i
			SET client_agreement_id = ?,
			    modified_on = NOW()
			WHERE i.application_id = ?
			  AND i.invoice_id = (
			      SELECT t.invoice_id
			      FROM transactions."transaction" t
			      WHERE t.transaction_id = ?
			        AND t.application_id = ?
			      LIMIT 1
			  )
			""";

	private static final String TAX_RATE_SQL = """
			SELECT
			    tr.tax_rate_id,
			    tra.tax_rate_percentage,
			    tra.tax_rate_allocation_id
			FROM finance.tax_rate tr
			JOIN finance.tax_rate_allocation tra
			  ON tra.tax_rate_id = tr.tax_rate_id
			WHERE tr.tax_group_id = ?
			  AND tr.level_id = ?
			""";

	private static final String BUNDLE_PRICE_BAND_SQL = "SELECT unit_price, down_payment_units FROM package.package_price_cycle_band WHERE package_price_cycle_band_id = ?";

	private static final String SQL_TYPE_NAME_BY_BUNDLE_ITEM_ID = """
			    SELECT lit.type_name
			    FROM package.package_item pi
			    JOIN items.item_version iv
			      ON iv.item_version_id = pi.item_version_id
			    JOIN items.item it
			      ON it.item_id = iv.item_id
			    JOIN items.lu_itemtypes lit
			      ON lit.item_type_id = it.item_type_id
			    WHERE pi.package_item_id = ?
			      AND COALESCE(pi.is_active, true) = true
			      AND pi.application_id = ?
			    LIMIT 1
			""";

	private static final String SQL_IS_PRORATE_APPLICABLE = """
			    SELECT EXISTS (
			        SELECT 1
			        FROM package.package_plan_template ppt
			        JOIN package.package_plan_template_term_config tc
			          ON tc.package_plan_template_id = ppt.package_plan_template_id
			        JOIN billing_config.proration_strategy ps
			          ON ps.proration_strategy_id = tc.default_proration_strategy_id
			        JOIN billing_config.billing_period_unit bpu
			          ON bpu.billing_period_unit_id = tc.billing_period_unit_id
			        WHERE ppt.package_plan_template_id = ?
			          AND ppt.application_id = ?
			          AND COALESCE(ppt.is_active, true) = true
			          AND COALESCE(tc.is_active, true) = true
			          AND COALESCE(ps.is_active, true) = true
			          AND COALESCE(bpu.is_active, true) = true
			          AND UPPER(ps.code) = 'DAILY'
			          AND UPPER(COALESCE(bpu.code, bpu.display_name, ''))
			              IN ('MONTHLY', 'MONTH', 'MON')
			    )
			""";

	private static final String SQL_INVOICE_SUMMARY = """
			WITH pay AS (
			    SELECT t.invoice_id, COALESCE(SUM(cpt.amount)::numeric(10,2), 0)::numeric(10,2) AS paid_amount
			    FROM "transactions"."transaction" t
			    JOIN client_payments.client_payment_transaction cpt
			      ON cpt.client_payment_transaction_id = t.client_payment_transaction_id
			    JOIN "transactions".invoice i_pay
			      ON i_pay.invoice_id = t.invoice_id
			    WHERE i_pay.client_role_id = ?
			      AND i_pay.application_id = ?
			      AND COALESCE(i_pay.is_active, true) = true
			    GROUP BY t.invoice_id
			)
			SELECT
			    i.invoice_id,
			    i.invoice_number,
			    i.invoice_date,
			    COALESCE(i.total_amount, 0)::numeric(10,2) AS amount,
			    COALESCE(p.paid_amount, 0)::numeric(10,2)   AS paid_amount,
			    0::numeric(10,2)                             AS write_off_amount,
			    GREATEST(
			        COALESCE(i.total_amount,0) - COALESCE(p.paid_amount,0) - 0,
			        0
			    )::numeric(10,2) AS balance_due,
			    s.status_name,
			    i.created_by
			FROM "transactions".invoice i
			JOIN "transactions".lu_invoice_status s
			  ON s.invoice_status_id = i.invoice_status_id
			LEFT JOIN pay p
			  ON p.invoice_id = i.invoice_id
			WHERE i.client_role_id = ?
			  AND i.application_id = ?
			  AND COALESCE(i.is_active, true) = true
			ORDER BY i.invoice_date DESC, i.invoice_number
			LIMIT ?
			OFFSET ?
			""";

	@Override
	public UUID saveInvoice(InvoiceDTO dto) {
		UUID invoiceId = UUID.randomUUID();
		UUID appId = AccessContext.applicationId();
		String sql = """
				INSERT INTO transactions.invoice (
				    invoice_id, invoice_number, invoice_date, client_role_id, billing_address,
				    invoice_status_id, total_amount, sub_total, tax_amount, discount_amount,
				    is_paid, application_id, created_on
				) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, NOW())
				""";

		cluboneJdbcTemplate.update(sql, invoiceId, dto.getInvoiceNumber(), dto.getInvoiceDate(), dto.getClientRoleId(),
				dto.getBillingAddress(), dto.getInvoiceStatusId(), dto.getTotalAmount(), dto.getSubTotal(),
				dto.getTaxAmount(), dto.getDiscountAmount(), dto.isPaid(), appId);

		return invoiceId;
	}

	@Override
	public UUID saveInvoiceV3(InvoiceDTO dto) {
		// 0) Canonical totals from line items (prevents header/lines mismatch)
		BigDecimal subtotal = dto.getSubTotal();
		BigDecimal discountSum = dto.getDiscountAmount();
		BigDecimal taxSum = dto.getTaxAmount();
		BigDecimal totalSum = dto.getTotalAmount();

		subtotal = subtotal.setScale(2, RoundingMode.HALF_UP);
		discountSum = discountSum.setScale(2, RoundingMode.HALF_UP);
		taxSum = taxSum.setScale(2, RoundingMode.HALF_UP);
		totalSum = totalSum.setScale(2, RoundingMode.HALF_UP);

		// Optionally compare with dto values and log if different
		if (dto.getTotalAmount() != null
				&& totalSum.compareTo(dto.getTotalAmount().setScale(2, RoundingMode.HALF_UP)) != 0) {
			logger.info("Invoice header total differs from computed: provided={}, computed={}", dto.getTotalAmount(),
					totalSum);
		}

		UUID invoiceId = UUID.randomUUID();
		UUID appId = AccessContext.applicationId();

		// 1) Insert invoice header (include created_by only if your table has it)
		final String insertInvoiceSql = """
								INSERT INTO transactions.invoice (
				    invoice_id,
				    invoice_number,
				    invoice_date,
				    client_role_id,
				    level_id,
				    billing_address,
				    invoice_status_id,
				    total_amount,
				    sub_total,
				    tax_amount,
				    discount_amount,
				    is_paid,
				    client_agreement_id,
				    billing_run_id,
				    billing_collection_type_id,
				    application_id,
				    created_on,
				    created_by
				) VALUES (
				    ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, NOW(), ?
				);
								""";

		cluboneJdbcTemplate.update(insertInvoiceSql, invoiceId, dto.getInvoiceNumber(), dto.getInvoiceDate(),
				dto.getClientRoleId(), dto.getLevelId(), dto.getBillingAddress(), dto.getInvoiceStatusId(), totalSum,
				subtotal, taxSum, discountSum, Boolean.TRUE.equals(dto.isPaid()), dto.getClientAgreementId(),
				dto.getBillingRunId(), dto.getBillingCollectionTypeId(), appId, dto.getCreatedBy());

		final String insertEntitySql = """
				INSERT INTO transactions.invoice_entity (
				    invoice_entity_id, parent_invoice_entity_id, invoice_id,
				    entity_type_id, entity_id, entity_description,
				    quantity, unit_price, discount_amount, tax_amount, total_amount,
				    created_on, created_by, price_plan_template_id, client_agreement_id,
				    billing_schedule_id, subscription_instance_id, cycle_number,
				    service_period_start, service_period_end, charge_line_kind_id, entity_version_id,
				    application_id
				) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, NOW(), ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
				""";

		final String insertTaxSql = """
				INSERT INTO transactions.invoice_entity_tax (
				    invoice_entity_tax_id, invoice_entity_id, tax_rate_id, tax_rate_percentage, tax_amount,
				    created_on, created_by,tax_rate_allocation_id
				) VALUES (?, ?, ?, ?, ?, NOW(), ?,?)
				""";

		final String insertPriceBandSql = """
				INSERT INTO transactions.invoice_entity_price_band (
				    invoice_entity_price_band_id,
				    invoice_entity_id,
				    price_cycle_band_id,
				    unit_price,
				    is_price_overridden,
				    is_active,
				    created_on,
				    created_by,
				    modified_on,
				    modified_by
				) VALUES (?, ?, ?, ?, COALESCE(?, false), true, NOW(), ?, NOW(), ?)
				""";

		List<InvoiceEntityDTO> lineItems = dto.getLineItems() != null ? dto.getLineItems() : List.of();
		List<Object[]> entityBatch = new ArrayList<>(lineItems.size());
		List<Object[]> taxBatch = new ArrayList<>();
		List<Object[]> priceBandBatch = new ArrayList<>();

		for (InvoiceEntityDTO li : lineItems) {
			UUID ieId = li.getInvoiceEntityId() != null ? li.getInvoiceEntityId() : UUID.randomUUID();
			li.setInvoiceEntityId(ieId);

			UUID parentId = li.getParentInvoiceEntityId();

			entityBatch.add(new Object[] {
					ieId, parentId, invoiceId, li.getEntityTypeId(),
					li.getEntityId(), li.getEntityDescription(), li.getQuantity(), li.getUnitPrice(),
					li.getDiscountAmount(), li.getTaxAmount(), li.getTotalAmount(),
					dto.getCreatedBy(), li.getPricePlanTemplateId(),
					li.getClientAgreementId(),
					li.getBillingScheduleId(), li.getSubscriptionInstanceId(), li.getCycleNumber(),
					li.getServicePeriodStart(), li.getServicePeriodEnd(), li.getChargeLineKindId(),
					li.getEntityVersionId(), appId
			});

			if (li.getTaxes() != null && !li.getTaxes().isEmpty()) {
				for (InvoiceEntityTaxDTO t : li.getTaxes()) {
					if (t.getTaxRateId() == null || t.getTaxRateAllocationId() == null) {
						continue;
					}
					taxBatch.add(new Object[] {
							UUID.randomUUID(), ieId, t.getTaxRateId(), t.getTaxRate(),
							t.getTaxAmount(), dto.getCreatedBy(), t.getTaxRateAllocationId()
					});
				}
			}

			if (li.getPriceBands() != null && !li.getPriceBands().isEmpty()) {
				for (InvoiceEntityPriceBandDTO pb : li.getPriceBands()) {
					UUID iepbId = UUID.randomUUID();
					UUID priceCycleBandId = pb.getPriceCycleBandId();
					BigDecimal unitPrice = pb.getUnitPrice() == null ? BigDecimal.ZERO : pb.getUnitPrice();
					Boolean overridden = Boolean.TRUE.equals(pb.getIsPriceOverridden());
					priceBandBatch.add(new Object[] {
							iepbId, ieId, priceCycleBandId, unitPrice, overridden,
							dto.getCreatedBy(), dto.getCreatedBy()
					});
				}
			}
		}

		if (!entityBatch.isEmpty()) {
			cluboneJdbcTemplate.batchUpdate(insertEntitySql, entityBatch);
		}
		if (!taxBatch.isEmpty()) {
			cluboneJdbcTemplate.batchUpdate(insertTaxSql, taxBatch);
		}
		if (!priceBandBatch.isEmpty()) {
			cluboneJdbcTemplate.batchUpdate(insertPriceBandSql, priceBandBatch);
		}

		saveInvoiceEntityPromotions(dto.getLineItems(), dto.getCreatedBy());
		return invoiceId;
	}

	/** Helpers */
	private static BigDecimal nvl(BigDecimal v) {
		return v != null ? v : BigDecimal.ZERO;
	}

	private static BigDecimal toBD(Integer v) {
		return v != null ? BigDecimal.valueOf(v.longValue()) : BigDecimal.ZERO;
	}

	@Override
	public UUID saveTransaction(TransactionDTO dto) {
		// Inside TransactionDAOImpl.saveTransaction(TransactionDTO dto)

		UUID transactionId = UUID.randomUUID();
		UUID appId = AccessContext.applicationId();

		// 1) Insert transaction header (now also sets created_by)
		String sql = """
				    INSERT INTO transactions."transaction" (
				        transaction_id,
				        client_payment_transaction_id,
				        invoice_id,
				        transaction_date,
				        application_id,
				        created_on,
				        created_by
				    ) VALUES (?, ?, ?, ?, ?, NOW(), ?)
				""";

		cluboneJdbcTemplate.update(
				sql,
				transactionId,
				dto.getClientPaymentTransactionId(),
				dto.getInvoiceId(),
				dto.getTransactionDate(),
				appId,
				dto.getCreatedBy()
		);

		// 2) Resolve BUNDLE type id once (for header detection)
		UUID bundleTypeId = cluboneJdbcTemplate.queryForObject(
				"SELECT entity_type_id FROM transactions.lu_entity_type WHERE LOWER(entity_type) = LOWER('BUNDLE')",
				UUID.class);

		// We'll remember the last bundle header's TE id
		UUID lastBundleHeaderId = null;

		for (TransactionEntityDTO entity : dto.getLineItems()) {
			UUID teId = UUID.randomUUID();

			// Decide parent: if caller didn't set it and we have a recent bundle header,
			// attach to it
			UUID parentIdToUse = entity.getParentTransactionEntityId();
			boolean isBundleHeader = bundleTypeId.equals(entity.getEntityTypeId());
			if (parentIdToUse == null && lastBundleHeaderId != null && !isBundleHeader) {
				parentIdToUse = lastBundleHeaderId;
			}

			String entitySql = """
					    INSERT INTO transactions.transaction_entity (
					        transaction_entity_id, parent_transaction_entity_id, transaction_id,
					        entity_type_id, entity_id, entity_description,
					        quantity, unit_price, discount_amount, tax_amount, total_amount,
					        created_on, created_by
					    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, NOW(), ?)
					""";

			cluboneJdbcTemplate.update(entitySql, teId, parentIdToUse, transactionId, entity.getEntityTypeId(),
					entity.getEntityId(), entity.getEntityDescription(), entity.getQuantity(), entity.getUnitPrice(),
					entity.getDiscountAmount(), entity.getTaxAmount(), entity.getTotalAmount(), dto.getCreatedBy() // <--
																													// added
			);

			// If this row is a BUNDLE header, remember it so subsequent rows attach to it
			if (isBundleHeader) {
				lastBundleHeaderId = teId;
			}

			// 3) Taxes (null-safe) â€” also include created_by
			if (entity.getTaxes() != null && !entity.getTaxes().isEmpty()) {
				for (TransactionEntityTaxDTO tax : entity.getTaxes()) {
					UUID taxId = UUID.randomUUID();
					String taxSql = """
							    INSERT INTO transactions.transaction_entity_tax (
							        transaction_entity_tax_id, transaction_entity_id, tax_rate_id,
							        tax_rate_percentage, tax_amount, created_on, created_by
							    ) VALUES (?, ?, ?, ?, ?, NOW(), ?)
							""";
					cluboneJdbcTemplate.update(taxSql, taxId, teId, tax.getTaxRateId(), tax.getTaxRate(),
							tax.getTaxAmount(), dto.getCreatedBy() // <-- added
					);
				}
			}
		}

		return transactionId;

	}

	@Override
	public String findInvoiceNumber(UUID invoiceId) {
		return cluboneJdbcTemplate.queryForObject(
				"SELECT invoice_number FROM transactions.invoice WHERE invoice_id = ? AND application_id = ?",
				String.class, invoiceId, AccessContext.applicationId());
	}

	@Override
	public UUID findTransactionIdByInvoiceId(UUID invoiceId) {
		List<UUID> ids = cluboneJdbcTemplate.query("""
				    SELECT transaction_id
				    FROM transactions."transaction"
				    WHERE invoice_id = ?
				      AND application_id = ?
				      AND COALESCE(is_active, true) = true
				    ORDER BY transaction_date DESC NULLS LAST,
				             created_on DESC NULLS LAST
				    LIMIT 1
				""", (rs, rn) -> UUID.fromString(rs.getString(1)), invoiceId, AccessContext.applicationId());
		return ids.isEmpty() ? null : ids.get(0);
	}

	@Override
	public UUID findClientPaymentTxnIdByTransactionId(UUID transactionId) {
		return cluboneJdbcTemplate.queryForObject("""
				    SELECT client_payment_transaction_id
				      FROM transactions.transaction
				     WHERE transaction_id = ?
				       AND application_id = ?
				""", UUID.class, transactionId, AccessContext.applicationId());
	}

	@Override
	public UUID findInvoiceStatusIdByName(String statusName) {
		if (statusName == null || statusName.isBlank()) {
			throw new IllegalArgumentException("invoice status name is required");
		}
		String key = statusName.trim().toLowerCase(Locale.ROOT);
		try {
			return INVOICE_STATUS_ID_CACHE.get(key, k -> cluboneJdbcTemplate.queryForObject("""
					SELECT invoice_status_id
					FROM transactions.lu_invoice_status
					WHERE LOWER(TRIM(status_name)) = LOWER(TRIM(?))
					  AND COALESCE(is_active, true) = true
					LIMIT 1
					""", UUID.class, statusName.trim()));
		} catch (EmptyResultDataAccessException e) {
			throw new IllegalStateException("No active lu_invoice_status row for status_name=" + statusName.trim(), e);
		}
	}

	@Override
	public Optional<UUID> tryFindInvoiceStatusIdByName(String statusName) {
		if (statusName == null || statusName.isBlank()) {
			return Optional.empty();
		}
		String key = statusName.trim().toLowerCase(Locale.ROOT);
		UUID cached = INVOICE_STATUS_ID_CACHE.getIfPresent(key);
		if (cached != null) {
			return Optional.of(cached);
		}
		try {
			UUID id = cluboneJdbcTemplate.queryForObject("""
					SELECT invoice_status_id
					FROM transactions.lu_invoice_status
					WHERE LOWER(TRIM(status_name)) = LOWER(TRIM(?))
					  AND COALESCE(is_active, true) = true
					LIMIT 1
					""", UUID.class, statusName.trim());
			if (id != null) {
				INVOICE_STATUS_ID_CACHE.put(key, id);
			}
			return Optional.ofNullable(id);
		} catch (EmptyResultDataAccessException e) {
			return Optional.empty();
		}
	}

	@Override
	public UUID findTransactionIdByInvoiceAndClientPaymentTransaction(UUID invoiceId,
			UUID clientPaymentTransactionId) {
		if (invoiceId == null || clientPaymentTransactionId == null) {
			return null;
		}
		List<UUID> ids = cluboneJdbcTemplate.query("""
				SELECT transaction_id
				  FROM transactions."transaction"
				 WHERE invoice_id = ?
				   AND client_payment_transaction_id = ?
				   AND application_id = ?
				   AND COALESCE(is_active, true) = true
				 ORDER BY transaction_date DESC NULLS LAST,
				          created_on DESC NULLS LAST
				 LIMIT 1
				""", (rs, rn) -> UUID.fromString(rs.getString(1)), invoiceId, clientPaymentTransactionId,
				AccessContext.applicationId());
		return ids.isEmpty() ? null : ids.get(0);
	}

	@Override
	public void updateInvoiceStatusAndPaidFlag(UUID invoiceId, UUID statusId, boolean paid, UUID modifiedBy) {
		cluboneJdbcTemplate.update("""
				    UPDATE transactions.invoice
				       SET invoice_status_id = ?, is_paid = ?, modified_on = NOW(), modified_by = ?
				     WHERE invoice_id = ?
				       AND application_id = ?
				""", statusId, paid, modifiedBy, invoiceId, AccessContext.applicationId());
	}

	@Override
	public String currentInvoiceStatusName(UUID invoiceId) {
		return cluboneJdbcTemplate.queryForObject("""
				    SELECT s.status_name
				      FROM transactions.invoice i
				      JOIN transactions.lu_invoice_status s ON s.invoice_status_id = i.invoice_status_id
				     WHERE i.invoice_id = ?
				       AND i.application_id = ?
				""", String.class, invoiceId, AccessContext.applicationId());
	}

	@Override
	public List<BundleComponent> findBundleComponents(UUID bundleId) {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public UUID findEntityTypeIdByName(String name) {
		String key = name == null ? "" : name.trim().toLowerCase(Locale.ROOT);
		String sql = """
				    SELECT entity_type_id
				    FROM transactions.lu_entity_type
				    WHERE LOWER(entity_type) = LOWER(?)
				      AND is_active = true
				    LIMIT 1
				""";
		return ENTITY_TYPE_ID_BY_NAME_CACHE.get(key, k -> cluboneJdbcTemplate.queryForObject(sql, UUID.class, name));
	}

	private static final String SQL = """
			WITH RECURSIVE ancestors AS (
			    SELECT l.level_id, l.parent_level_id, 0 AS depth
			    FROM location.levels l
			    WHERE l.level_id = ?
			    UNION ALL
			    SELECT p.level_id, p.parent_level_id, a.depth + 1
			    FROM location.levels p
			    JOIN ancestors a
			      ON a.parent_level_id = p.level_id
			),
			level_candidates AS (
			    SELECT level_id, depth
			    FROM ancestors
			    UNION ALL
			    SELECT NULL::uuid AS level_id, 100000 AS depth
			)
			SELECT DISTINCT
			    p.description,
			    i.item_id,
			    i.item_description,
			    pi.item_quantity,
			    eff_ip.price AS "itemPrice",
			    i.tax_group_id,
			    eff_pp.price,
			    NULL::boolean AS is_continuous,
			    NULL::integer AS recurrence_count,
			    eff_pp.price_level_id
			FROM package.package p
			JOIN package.package_version pv
			  ON pv.package_version_id = p.current_version_id
			 AND pv.package_id = p.package_id
			 AND pv.application_id = p.application_id
			 AND COALESCE(pv.is_active, true) = true
			JOIN package.package_item pi
			  ON pi.package_id = p.package_id
			 AND pi.application_id = p.application_id
			 AND COALESCE(pi.is_active, true) = true
			JOIN items.item_version iv
			  ON iv.item_version_id = pi.item_version_id
			JOIN items.item i
			  ON i.item_id = iv.item_id

			LEFT JOIN LATERAL (
			    SELECT ip.price
			    FROM items.item_price ip
			    JOIN level_candidates lc
			      ON (ip.level_id = lc.level_id)
			      OR (ip.level_id IS NULL AND lc.level_id IS NULL)
			    WHERE ip.item_version_id = pi.item_version_id
			      AND COALESCE(ip.is_active, true) = true
			      AND (ip.start_at_local IS NULL OR ip.start_at_local <= NOW())
			      AND (ip.end_at_local IS NULL OR ip.end_at_local >= NOW())
			    ORDER BY lc.depth ASC, ip.created_on DESC
			    LIMIT 1
			) eff_ip ON TRUE

			LEFT JOIN LATERAL (
			    SELECT
			        pp.price,
			        pl.level_id AS price_level_id
			    FROM package.package_location pl
			    JOIN package.package_price pp
			      ON pp.package_location_id = pl.package_location_id
			     AND pp.package_item_id = pi.package_item_id
			     AND pp.package_version_id = pv.package_version_id
			     AND pp.application_id = p.application_id
			     AND COALESCE(pp.is_active, true) = true
			    JOIN level_candidates lc
			      ON (pl.level_id = lc.level_id)
			      OR (pl.level_id IS NULL AND lc.level_id IS NULL)
			    WHERE pl.package_version_id = pv.package_version_id
			      AND pl.application_id = p.application_id
			      AND COALESCE(pl.is_active, true) = true
			      AND pl.valid_from <= NOW()
			      AND (pl.valid_to IS NULL OR pl.valid_to >= NOW())
			    ORDER BY lc.depth ASC, pl.valid_from DESC
			    LIMIT 1
			) eff_pp ON TRUE

			WHERE p.package_id = ?
			  AND p.application_id = ?
			  AND COALESCE(p.is_active, true) = true
			  AND eff_pp.price IS NOT NULL
			ORDER BY pi.display_order NULLS LAST, i.item_description
			""";

	private static final RowMapper<BundleItemPriceDTO> ROW_MAPPER = new RowMapper<>() {
		@Override
		public BundleItemPriceDTO mapRow(ResultSet rs, int rowNum) throws SQLException {
			String description = rs.getString("description");
			UUID itemId = (UUID) rs.getObject("item_id");
			String itemDescription = rs.getString("item_description");

			BigDecimal itemQuantity = rs.getBigDecimal("item_quantity");
			BigDecimal itemPrice = rs.getBigDecimal("itemPrice"); // alias from SQL
			UUID taxGroupId = (UUID) rs.getObject("tax_group_id");
			BigDecimal bundlePrice = rs.getBigDecimal("price"); // bp.price

			Boolean isContinuous = (Boolean) rs.getObject("is_continuous"); // handles nullable boolean
			Integer recurrenceCount = (Integer) rs.getObject("recurrence_count"); // handles nullable int

			return new BundleItemPriceDTO(description, itemId, itemDescription, itemQuantity, itemPrice, taxGroupId,
					bundlePrice, isContinuous, recurrenceCount);
		}
	};

	@Override
	public List<BundleItemPriceDTO> getBundleItemsWithPrices(UUID bundleId, UUID levelId) {
		return cluboneJdbcTemplate.query(
				SQL,
				ROW_MAPPER,
				levelId,
				bundleId,
				AccessContext.applicationId()
		);
	}

	private static final RowMapper<EntityTypeDTO> ENTITY_TYPE_ROW_MAPPER = new RowMapper<>() {
		@Override
		public EntityTypeDTO mapRow(ResultSet rs, int rowNum) throws SQLException {
			return new EntityTypeDTO(rs.getString("entity_type"));
		}
	};

	@Override
	public Optional<EntityTypeDTO> getEntityTypeById(UUID entityTypeId) {
		if (entityTypeId == null) {
			return Optional.empty();
		}
		EntityTypeDTO cached = ENTITY_TYPE_BY_ID_CACHE.getIfPresent(entityTypeId);
		if (cached != null) {
			return Optional.of(cached);
		}
		Optional<EntityTypeDTO> found = cluboneJdbcTemplate.query(ENTITY_TYPE_SQL, ENTITY_TYPE_ROW_MAPPER, entityTypeId)
				.stream().findFirst();
		found.ifPresent(dto -> ENTITY_TYPE_BY_ID_CACHE.put(entityTypeId, dto));
		return found;
	}

	private static final RowMapper<ItemPriceDTO> ITEM_PRICE_ROW_MAPPER = new RowMapper<>() {
		@Override
		public ItemPriceDTO mapRow(ResultSet rs, int rowNum) throws SQLException {
			ItemPriceDTO dto = new ItemPriceDTO();
			dto.setItemDescription(rs.getString("item_description"));
			dto.setItemPrice(rs.getBigDecimal("itemPrice"));
			dto.setTaxGroupId((UUID) rs.getObject("tax_group_id"));
			return dto;
		}
	};

	@Override
	public Optional<ItemPriceDTO> getItemPriceByItemAndLevel(UUID itemId, UUID levelId) {
		return cluboneJdbcTemplate.query(ITEM_PRICE_SQL, ITEM_PRICE_ROW_MAPPER, levelId, itemId, itemId).stream()
				.findFirst();
	}

	@Override
	public UUID saveTransactionV3(TransactionDTO dto) {
		// Inside TransactionDAOImpl.saveTransaction(TransactionDTO dto)

		UUID transactionId = UUID.randomUUID();
		UUID appId = AccessContext.applicationId();

		// 1) Insert transaction header (now also sets created_by)
		String sql = """
				    INSERT INTO transactions.transaction (
				        transaction_id,  client_payment_transaction_id,
				         invoice_id, transaction_date, application_id, created_on, created_by
				    ) VALUES (?,  ?,  ?, ?, ?, NOW(), ?)
				""";

		cluboneJdbcTemplate.update(sql, transactionId, dto.getClientPaymentTransactionId(), dto.getInvoiceId(),
				dto.getTransactionDate(), appId, dto.getCreatedBy()
		);

		return transactionId;
	}

	private static final RowMapper<InvoiceSummaryDTO> INVOICE_SUMMARY_ROW_MAPPER = new RowMapper<>() {
		@Override
		public InvoiceSummaryDTO mapRow(ResultSet rs, int rowNum) throws SQLException {
			InvoiceSummaryDTO dto = new InvoiceSummaryDTO();
			dto.setClientRoleId((UUID) rs.getObject("client_role_id"));
			dto.setTotalAmount(rs.getBigDecimal("total_amount"));
			dto.setLevelId((UUID) rs.getObject("level_id"));
			dto.setClientAgreementId((UUID) rs.getObject("client_agreement_id"));
			return dto;
		}
	};

	@Override
	public Optional<InvoiceSummaryDTO> getInvoiceSummaryById(UUID invoiceId) {
		return cluboneJdbcTemplate
				.query(INVOICE_SUMMARY_SQL, INVOICE_SUMMARY_ROW_MAPPER, invoiceId, AccessContext.applicationId())
				.stream().findFirst();
	}

	@Override
	public int updateClientAgreementId(UUID transactionId, UUID clientAgreementId) {
		UUID appId = AccessContext.applicationId();
		return cluboneJdbcTemplate.update(
				UPDATE_TRANSACTION_CLIENT_AGREEMENT_SQL,
				clientAgreementId,
				appId,
				transactionId,
				appId
		);
	}

	private static final RowMapper<TaxRateAllocationDTO> TAX_RATE_ROW_MAPPER = new RowMapper<>() {
		@Override
		public TaxRateAllocationDTO mapRow(ResultSet rs, int rowNum) throws SQLException {
			TaxRateAllocationDTO dto = new TaxRateAllocationDTO();
			dto.setTaxRateId((UUID) rs.getObject("tax_rate_id"));
			dto.setTaxRatePercentage(rs.getBigDecimal("tax_rate_percentage"));
			dto.setTaxRateAllocationId((UUID) rs.getObject("tax_rate_allocation_id"));
			return dto;
		}
	};

	@Override
	public List<TaxRateAllocationDTO> getTaxRatesByGroupAndLevel(UUID taxGroupId, UUID levelId) {
		if (taxGroupId == null || levelId == null) {
			return Collections.emptyList();
		}
		return cluboneJdbcTemplate.query(TAX_RATE_SQL, TAX_RATE_ROW_MAPPER, taxGroupId, levelId);
	}

	@Override
	public List<InvoiceFlatRow> findInvoicesWithLatestTxnByClientRole(UUID clientRoleId) {
		UUID appId = AccessContext.applicationId();
		String sql = """
				SELECT
				    i.invoice_id,
				    i.invoice_number,
				    i.invoice_date::date AS invoice_date,
				    i.total_amount,
				    i.sub_total,
				    i.tax_amount,
				    i.discount_amount,
				    lt.transaction_number AS transaction_code,
				    i.client_agreement_id,
				    lt.client_payment_transaction_id,
				    lt.transaction_date
				FROM transactions.invoice i
				LEFT JOIN (
				    SELECT DISTINCT ON (t.invoice_id)
				        t.invoice_id,
				        t.transaction_number,
				        t.client_payment_transaction_id,
				        t.transaction_date,
				        t.created_on
				    FROM transactions."transaction" t
				    JOIN transactions.invoice i2
				      ON i2.invoice_id = t.invoice_id
				     AND i2.application_id = t.application_id
				    WHERE i2.client_role_id = ?
				      AND i2.application_id = ?
				      AND COALESCE(t.is_active, true) = true
				    ORDER BY
				        t.invoice_id,
				        t.transaction_date DESC NULLS LAST,
				        t.created_on DESC NULLS LAST
				) lt ON lt.invoice_id = i.invoice_id
				WHERE i.client_role_id = ?
				  AND i.application_id = ?
				  AND COALESCE(i.is_active, true) = true
				ORDER BY
				    i.invoice_date DESC NULLS LAST,
				    i.invoice_number DESC NULLS LAST
				LIMIT 200
				""";

		return cluboneJdbcTemplate.query(
				sql,
				(rs, rowNum) -> mapInvoiceFlatRow(rs),
				clientRoleId,
				appId,
				clientRoleId,
				appId
		);
	}

	@Override
	public List<InvoiceEntityRow> findEntitiesByInvoiceIds(List<UUID> invoiceIds) {
		if (invoiceIds.isEmpty()) {
			return Collections.emptyList();
		}

		UUID appId = AccessContext.applicationId();
		// Build a dynamic IN clause (?, ?, ?, ...)
		String inSql = String.join(",", Collections.nCopies(invoiceIds.size(), "?"));
		String sql = """
				SELECT
				    ie.invoice_id,
				    ie.invoice_entity_id,
				    ie.parent_invoice_entity_id,
				    ie.entity_description,
				    ie.entity_id,
				    ie.quantity,
				    ie.unit_price,
				    ie.discount_amount,
				    ie.tax_amount,
				    ie.total_amount
				FROM "transactions".invoice_entity ie
				WHERE ie.invoice_id IN (%s)
				  AND ie.application_id = ?
				ORDER BY ie.parent_invoice_entity_id NULLS FIRST, ie.invoice_entity_id
				""".formatted(inSql);

		Object[] args = new Object[invoiceIds.size() + 1];
		for (int i = 0; i < invoiceIds.size(); i++) {
			args[i] = invoiceIds.get(i);
		}
		args[invoiceIds.size()] = appId;
		return cluboneJdbcTemplate.query(sql, (rs, rowNum) -> mapInvoiceEntityRow(rs), args);
	}

	private InvoiceFlatRow mapInvoiceFlatRow(ResultSet rs) throws SQLException {
		InvoiceFlatRow r = new InvoiceFlatRow();
		r.setInvoiceId(UUID.fromString(rs.getString("invoice_id")));
		r.setInvoiceNumber(rs.getString("invoice_number"));
		r.setInvoiceDate(rs.getDate("invoice_date").toLocalDate());
		r.setTotalAmount(rs.getBigDecimal("total_amount"));
		r.setSubTotal(rs.getBigDecimal("sub_total"));
		r.setTaxAmount(rs.getBigDecimal("tax_amount"));
		r.setDiscountAmount(rs.getBigDecimal("discount_amount"));
		r.setTransactionCode(rs.getString("transaction_code"));

		String ca = rs.getString("client_agreement_id");
		r.setClientAgreementId(ca == null ? null : UUID.fromString(ca));

		String cpt = rs.getString("client_payment_transaction_id");
		r.setClientPaymentTransactionId(cpt == null ? null : UUID.fromString(cpt));

		OffsetDateTime odt = rs.getObject("transaction_date", OffsetDateTime.class);
		r.setTransactionDate(odt);
		return r;
	}

	private InvoiceEntityRow mapInvoiceEntityRow(ResultSet rs) throws SQLException {
		InvoiceEntityRow r = new InvoiceEntityRow();
		r.setInvoiceId(UUID.fromString(rs.getString("invoice_id")));
		r.setInvoiceEntityId(UUID.fromString(rs.getString("invoice_entity_id")));

		String parent = rs.getString("parent_invoice_entity_id");
		r.setParentInvoiceEntityId(parent == null ? null : UUID.fromString(parent));

		r.setEntityDescription(rs.getString("entity_description"));

		String entId = rs.getString("entity_id");
		r.setEntityId(entId == null ? null : UUID.fromString(entId));

		r.setQuantity(rs.getBigDecimal("quantity"));
		r.setUnitPrice(rs.getBigDecimal("unit_price"));
		r.setDiscountAmount(rs.getBigDecimal("discount_amount"));
		r.setTaxAmount(rs.getBigDecimal("tax_amount"));
		r.setTotalAmount(rs.getBigDecimal("total_amount"));
		return r;
	}

	@Override
	public UUID findTaxGroupIdForItem(UUID itemId, UUID levelId) {
		// Look for item_price scoped to given level
		final String sql = """
				    SELECT i.tax_group_id
				    FROM items.item_price ip
				    JOIN items.item_version iv on iv.item_version_id=ip.item_version_id
				    JOIN items.item i ON i.item_id = iv.item_id
				    WHERE i.item_id = ?
				      AND ip.level_id = ?
				      AND COALESCE(ip.is_active, true) = true
				      AND (ip.start_at_local IS NULL OR ip.start_at_local <= now())
				      AND (ip.end_at_local IS NULL OR ip.end_at_local >= now())
				    ORDER BY ip.created_on DESC
				    LIMIT 1
				""";

		UUID tg = firstUuid(sql, itemId, levelId);
		if (tg != null)
			return tg;

		// fallback: no level-specific row, get default item.tax_group_id
		final String fallback = """
				    SELECT i.tax_group_id
				    FROM items.item i
				    WHERE i.item_id = ?
				    LIMIT 1
				""";
		return firstUuid(fallback, itemId);
	}

	private UUID firstUuid(String sql, Object... params) {
		try {
			List<UUID> list = cluboneJdbcTemplate.query(sql, ps -> {
				for (int i = 0; i < params.length; i++) {
					ps.setObject(i + 1, params[i]);
				}
			}, (rs, rn) -> {
				try {
					return rs.getObject(1, UUID.class);
				} catch (Throwable ignore) {
					Object o = rs.getObject(1);
					return (o == null) ? null : (o instanceof UUID ? (UUID) o : UUID.fromString(o.toString()));
				}
			});
			return (list == null || list.isEmpty()) ? null : list.get(0);
		} catch (DataAccessException dae) {
			return null;
		}
	}

	@Override
	public Optional<DiscountDetailDTO> findBestDiscountForItemByIds(UUID itemId, UUID levelId, List<UUID> discountIds) {
		if (discountIds == null || discountIds.isEmpty())
			return Optional.empty();

		String inPlaceholders = discountIds.stream().map(x -> "?").collect(java.util.stream.Collectors.joining(", "));

		final String sql = """
						SELECT
				d.discount_id,
				d.calculation_type_id,
				d.adjustment_type_id,
				ct.name           AS calc_name,
				d.adjustment_amount,
				d.min_discount_percent,
				d.max_discount_percent,
				di.item_id,
				d.apply_to_level_id,
				d.created_on FROM discount.discount d
						LEFT JOIN discount.discount_item di
						       ON di.discount_id = d.discount_id
						      AND COALESCE(di.is_active, true) = true
						      AND di.item_id = ?
						JOIN discount.lu_calculation_type ct
						      ON ct.calculation_type_id = d.calculation_type_id
						WHERE d.discount_id IN (%s)
						  AND COALESCE(d.is_active, true) = true
						  AND d.start_date <= CURRENT_DATE
						  AND (d.end_date IS NULL OR d.end_date >= CURRENT_DATE)
						  AND (d.apply_to_level_id IS NULL OR d.apply_to_level_id = ?)
						  AND (d.available_for_any_item = true OR di.item_id IS NOT NULL)
						ORDER BY
						  CASE WHEN di.item_id IS NOT NULL THEN 0 ELSE 1 END,      -- item-specific first
						  CASE WHEN d.apply_to_level_id = ? THEN 0
						       WHEN d.apply_to_level_id IS NULL THEN 1 ELSE 2 END, -- exact level match first
						  d.created_on DESC
						LIMIT 1
						""".formatted(inPlaceholders); // or String.format(...)

		// Bind: itemId, <discountIds...>, levelId, levelId
		java.util.List<Object> params = new java.util.ArrayList<>();
		params.add(itemId);
		params.addAll(discountIds);
		params.add(levelId);
		params.add(levelId);

		try {
			java.util.List<DiscountDetailDTO> out = cluboneJdbcTemplate.query(sql, params.toArray(),
					(rs, rn) -> mapDiscount(rs));
			return (out == null || out.isEmpty()) ? Optional.empty() : java.util.Optional.of(out.get(0));
		} catch (org.springframework.dao.DataAccessException e) {
			return Optional.empty();
		}
	}

	/** Map row to DiscountDetailDTO with calc-name interpretation. */
	private DiscountDetailDTO mapDiscount(ResultSet rs) throws SQLException {
		DiscountDetailDTO dto = new DiscountDetailDTO();
		dto.setDiscountId(rs.getObject("discount_id", UUID.class));
		dto.setCalculationTypeId(rs.getObject("calculation_type_id", UUID.class));
		dto.setAdjustmentTypeId(rs.getObject("adjustment_type_id", UUID.class));

		String calcName = rs.getString("calc_name"); // "Percentage Based", "Amount Based - Quantity Price", "Amount
														// Based - Total Price"
		BigDecimal adj = rs.getBigDecimal("adjustment_amount");
		BigDecimal minP = rs.getBigDecimal("min_discount_percent");
		BigDecimal maxP = rs.getBigDecimal("max_discount_percent");

		if ("Percentage Based".equalsIgnoreCase(calcName)) {
			BigDecimal pct = (adj == null ? BigDecimal.ZERO : adj);
			if (minP != null && pct.compareTo(minP) < 0)
				pct = minP;
			if (maxP != null && pct.compareTo(maxP) > 0)
				pct = maxP;
			dto.setCalculationMode(CalculationMode.PERCENTAGE);
			dto.setDiscountRate(scale2(pct));
			dto.setDiscountAmount(null);
		} else if ("Amount Based - Quantity Price".equalsIgnoreCase(calcName)) {
			dto.setCalculationMode(CalculationMode.AMOUNT_PER_QTY);
			dto.setDiscountRate(null);
			dto.setDiscountAmount(scale2(adj));
		} else { // Amount Based - Total Price
			dto.setCalculationMode(CalculationMode.AMOUNT_PER_LINE);
			dto.setDiscountRate(null);
			dto.setDiscountAmount(scale2(adj));
		}
		return dto;
	}

	private static BigDecimal scale2(BigDecimal v) {
		return v == null ? null : v.setScale(2, java.math.RoundingMode.HALF_UP);
	}

	private static final RowMapper<BundlePriceCycleBandDTO> BUNDLE_PRICE_CYCLE_ROW_MAPPER = new RowMapper<>() {
		@Override
		public BundlePriceCycleBandDTO mapRow(ResultSet rs, int rowNum) throws SQLException {
			BigDecimal unitPrice = rs.getBigDecimal("unit_price");
			Integer downPaymentUnits = (Integer) rs.getObject("down_payment_units"); // handles NULLs
			return new BundlePriceCycleBandDTO(unitPrice, downPaymentUnits);
		}
	};

	@Override
	public List<BundlePriceCycleBandDTO> findByPriceCycleBandId(UUID priceCycleBandId) {
		return cluboneJdbcTemplate.query(BUNDLE_PRICE_BAND_SQL, BUNDLE_PRICE_CYCLE_ROW_MAPPER, priceCycleBandId);
	}

	@Override
	public Optional<String> findTypeNameByBundleItemId(UUID bundleItemId) {
		try {
			String name = cluboneJdbcTemplate.queryForObject(
					SQL_TYPE_NAME_BY_BUNDLE_ITEM_ID,
					String.class,
					bundleItemId,
					AccessContext.applicationId()
			);
			return Optional.ofNullable(name);
		} catch (EmptyResultDataAccessException e) {
			return Optional.empty();
		}
	}

	@Override
	public boolean isProrateApplicable(UUID planTemplateId) {
		Boolean ok = cluboneJdbcTemplate.queryForObject(
				SQL_IS_PRORATE_APPLICABLE,
				Boolean.class,
				planTemplateId,
				AccessContext.applicationId()
		);
		return ok != null && ok;
	}

	@Override
	public List<io.clubone.transaction.v2.vo.InvoiceSummaryDTO> findByClientRole(UUID clientRoleId, Integer limit,
			Integer offset) {
		UUID appId = AccessContext.applicationId();
		int safeLimit = Math.min(Math.max(limit != null ? limit : 100, 1), 200);
		int safeOffset = Math.max(offset != null ? offset : 0, 0);
		return cluboneJdbcTemplate.query(SQL_INVOICE_SUMMARY, ps -> {
			ps.setObject(1, clientRoleId);
			ps.setObject(2, appId);
			ps.setObject(3, clientRoleId);
			ps.setObject(4, appId);
			ps.setInt(5, safeLimit);
			ps.setInt(6, safeOffset);
		}, (rs, rn) -> {
			io.clubone.transaction.v2.vo.InvoiceSummaryDTO dto = new io.clubone.transaction.v2.vo.InvoiceSummaryDTO();
			dto.setInvoiceId((UUID) rs.getObject("invoice_id"));
			dto.setInvoiceNumber(rs.getString("invoice_number"));

			Timestamp ts = rs.getTimestamp("invoice_date");
			dto.setInvoiceDate(ts != null ? ts.toLocalDateTime().toLocalDate() : null);

			dto.setAmount(rs.getBigDecimal("amount"));
			dto.setBalanceDue(rs.getBigDecimal("balance_due"));
			dto.setWriteOff(rs.getBigDecimal("write_off_amount"));
			dto.setStatus(rs.getString("status_name"));

			dto.setCreatedBy((UUID) rs.getObject("created_by"));
			dto.setSalesRep(null); // No people table in schema; map externally if desired

			// Safety: ensure scale(2)
			dto.setAmount(s2(dto.getAmount()));
			dto.setBalanceDue(s2(dto.getBalanceDue()));
			dto.setWriteOff(s2(dto.getWriteOff()));
			return dto;
		});
	}

	private static BigDecimal s2(BigDecimal v) {
		return v == null ? null : v.setScale(2, java.math.RoundingMode.HALF_UP);
		// Frontend can do currency formatting ($) as in the screenshot.
	}

	private static final RowMapper<InvoiceDetailRaw> RAW_MAPPER = new RowMapper<>() {
		@Override
		public InvoiceDetailRaw mapRow(ResultSet rs, int rowNum) throws SQLException {
			return new InvoiceDetailRaw(
					// invoice
					(UUID) rs.getObject("invoice_id"), rs.getString("invoice_number"),
					rs.getObject("invoice_date", java.time.LocalDate.class), rs.getBigDecimal("amount"),
					rs.getBigDecimal("balance_due"), rs.getBigDecimal("write_off"), rs.getString("status"),
					rs.getString("sales_rep"), (UUID) rs.getObject("level_id"),

					// billing history
					(UUID) rs.getObject("subscription_instance_id"), rs.getBigDecimal("amount_gross_incl_tax"),

					// instance
					(UUID) rs.getObject("subscription_plan_id"), rs.getObject("start_date", java.time.LocalDate.class),
					rs.getObject("next_billing_date", java.time.LocalDate.class),
					rs.getObject("last_billed_on", java.time.LocalDate.class),
					rs.getObject("end_date", java.time.LocalDate.class), (Integer) rs.getObject("current_cycle_number"),

					// plan
					(Integer) rs.getObject("interval_count"), (UUID) rs.getObject("subscription_frequency_id"),
					rs.getObject("contract_start_date", java.time.LocalDate.class),
					rs.getObject("contract_end_date", java.time.LocalDate.class), (UUID) rs.getObject("entity_id"),
					(UUID) rs.getObject("entity_type_id"),

					// frequency/terms
					rs.getString("frequency_name"), rs.getInt("remaining_cycles"),

					// invoice_entity joins
					(UUID) rs.getObject("child_entity_id"), (UUID) rs.getObject("child_entity_type_id"),
					(UUID) rs.getObject("parent_entity_id"), (UUID) rs.getObject("parent_entity_type_id"),

					// template
					(Integer) rs.getObject("template_total_cycles"), (UUID) rs.getObject("template_level_id"),

					// final cycles
					(Integer) rs.getObject("total_cycles"));
		}
	};

	@Override
	public Optional<InvoiceDetailRaw> loadInvoiceAggregate(UUID invoiceId) {
		final String sql = """
				WITH inv AS (
				    SELECT i.*
				    FROM transactions.invoice i
				    WHERE i.invoice_id = ?
				      AND i.application_id = ?
				),
				sbh_pick AS (
				    SELECT sbh.*
				    FROM client_subscription_billing.subscription_billing_history sbh
				    JOIN inv i
				      ON i.invoice_id = sbh.invoice_id
				    ORDER BY
				        sbh.billing_attempt_on DESC NULLS LAST,
				        sbh.created_on DESC NULLS LAST
				    LIMIT 1
				),
				ie_pick AS (
				    SELECT ie.*
				    FROM transactions.invoice_entity ie
				    JOIN inv i
				      ON i.invoice_id = ie.invoice_id
				     AND i.application_id = ie.application_id
				    WHERE COALESCE(ie.is_active, true) = true
				    ORDER BY
				        (ie.price_plan_template_id IS NOT NULL) DESC,
				        ie.total_amount DESC NULLS LAST,
				        ie.created_on DESC NULLS LAST
				    LIMIT 1
				),
				payment_totals AS (
				    SELECT
				        t.invoice_id,
				        COALESCE(SUM(cpt.amount), 0)::numeric AS paid_amount
				    FROM transactions."transaction" t
				    JOIN client_payments.client_payment_transaction cpt
				      ON cpt.client_payment_transaction_id =
				         t.client_payment_transaction_id
				    JOIN inv i
				      ON i.invoice_id = t.invoice_id
				     AND i.application_id = t.application_id
				    WHERE COALESCE(t.is_active, true) = true
				    GROUP BY t.invoice_id
				)
				SELECT
				    i.invoice_id,
				    i.invoice_number,
				    i.invoice_date::date AS invoice_date,
				    COALESCE(i.total_amount, 0) AS amount,
				    GREATEST(
				        COALESCE(i.total_amount, 0) -
				        COALESCE(pt.paid_amount, 0),
				        0
				    ) AS balance_due,
				    0::numeric AS write_off,
				    COALESCE(lis.status_name, 'UNKNOWN') AS status,
				    NULL::text AS sales_rep,
				    i.level_id,

				    sbh.subscription_instance_id,
				    sbh.invoice_total_amount AS amount_gross_incl_tax,

				    si.subscription_plan_id,
				    si.billing_start_date AS start_date,
				    si.next_billing_date,
				    si.last_billed_on,
				    si.billing_end_date AS end_date,
				    si.current_cycle_number,

				    sbcs.interval_count,
				    sbcs.billing_period_unit_id AS subscription_frequency_id,
				    si.billing_start_date AS contract_start_date,
				    si.billing_end_date AS contract_end_date,
				    ie.entity_id,
				    ie.entity_type_id,

				    COALESCE(bpu.code, bpu.display_name) AS frequency_name,

				    COALESCE(
				        CASE
				            WHEN sp.term_total_cycles IS NOT NULL
				             AND si.current_cycle_number IS NOT NULL
				            THEN GREATEST(
				                sp.term_total_cycles - si.current_cycle_number,
				                0
				            )
				            ELSE 0
				        END,
				        0
				    ) AS remaining_cycles,

				    ie.entity_id AS child_entity_id,
				    ie.entity_type_id AS child_entity_type_id,
				    iep.entity_id AS parent_entity_id,
				    iep.entity_type_id AS parent_entity_type_id,

				    tc.total_cycles AS template_total_cycles,
				    ppt.level_id AS template_level_id,

				    COALESCE(
				        tc.total_cycles,
				        sp.term_total_cycles
				    ) AS total_cycles
				FROM inv i
				LEFT JOIN sbh_pick sbh
				  ON TRUE
				LEFT JOIN client_subscription_billing.subscription_instance si
				  ON si.subscription_instance_id = sbh.subscription_instance_id
				LEFT JOIN client_subscription_billing.subscription_plan sp
				  ON sp.subscription_plan_id = si.subscription_plan_id
				LEFT JOIN LATERAL (
				    SELECT sbcs0.*
				    FROM client_subscription_billing.subscription_billing_config_snapshot sbcs0
				    WHERE sbcs0.subscription_plan_id = sp.subscription_plan_id
				    ORDER BY sbcs0.created_on DESC NULLS LAST
				    LIMIT 1
				) sbcs ON TRUE
				LEFT JOIN billing_config.billing_period_unit bpu
				  ON bpu.billing_period_unit_id = sbcs.billing_period_unit_id
				LEFT JOIN ie_pick ie
				  ON TRUE
				LEFT JOIN transactions.invoice_entity iep
				  ON iep.invoice_entity_id = ie.parent_invoice_entity_id
				 AND iep.application_id = i.application_id
				LEFT JOIN package.package_plan_template ppt
				  ON ppt.package_plan_template_id = ie.price_plan_template_id
				 AND ppt.application_id = i.application_id
				LEFT JOIN LATERAL (
				    SELECT tc0.*
				    FROM package.package_plan_template_term_config tc0
				    WHERE tc0.package_plan_template_id =
				          ppt.package_plan_template_id
				      AND tc0.application_id = i.application_id
				      AND COALESCE(tc0.is_active, true) = true
				    ORDER BY tc0.created_on DESC NULLS LAST
				    LIMIT 1
				) tc ON TRUE
				LEFT JOIN transactions.lu_invoice_status lis
				  ON lis.invoice_status_id = i.invoice_status_id
				LEFT JOIN payment_totals pt
				  ON pt.invoice_id = i.invoice_id
				""";

		try {
			return Optional.ofNullable(
					cluboneJdbcTemplate.queryForObject(
							sql,
							RAW_MAPPER,
							invoiceId,
							AccessContext.applicationId()
					)
			);
		} catch (EmptyResultDataAccessException ex) {
			return Optional.empty();
		}
	}

	@Override
	public BigDecimal findEffectivePriceForCycle(UUID subscriptionPlanId, int cycleNumber) {
		final String sql = """
				SELECT pscp.unit_price
				FROM client_subscription_billing.subscription_purchase_snapshot sps
				JOIN client_subscription_billing.subscription_purchase_snapshot_cycle_price pscp
				  ON pscp.subscription_purchase_snapshot_id =
				     sps.subscription_purchase_snapshot_id
				WHERE sps.subscription_plan_id = ?
				  AND pscp.cycle_start <= ?
				  AND (
				      pscp.cycle_end IS NULL
				      OR pscp.cycle_end >= ?
				  )
				ORDER BY
				    sps.captured_on DESC NULLS LAST,
				    pscp.cycle_start DESC
				LIMIT 1
				""";

		return cluboneJdbcTemplate.query(
				sql,
				rs -> rs.next() ? rs.getBigDecimal("unit_price") : null,
				subscriptionPlanId,
				cycleNumber,
				cycleNumber
		);
	}

	private UUID resolveCachedLookupId(String cacheKey, String sql) {
		return ACTIVATE_LOOKUP_CACHE.get(cacheKey, k -> cluboneJdbcTemplate.queryForObject(sql, UUID.class));
	}

	@Override
	public void activateAgreementAndClientStatusForInvoice(UUID invoiceId, UUID actorId) {
		if (invoiceId == null) {
			throw new IllegalArgumentException("invoiceId must not be null");
		}
		if (actorId == null) {
			throw new IllegalArgumentException("actorId must not be null");
		}

		// Resolve lookup IDs once (process cache) instead of correlated subqueries per UPDATE.
		UUID activeAgreementStatusId = resolveCachedLookupId("ca_status_ACTIVE", """
				SELECT las.client_agreement_status_id
				FROM client_agreements.lu_client_agreement_status las
				WHERE las.code = 'ACTIVE'
				  AND las.is_active = TRUE
				LIMIT 1
				""");
		UUID activeClientStatusId = resolveCachedLookupId("client_status_Active", """
				SELECT lcs.lu_client_status_id
				FROM clients.lu_client_status lcs
				WHERE lcs.client_status_type = 'Active'
				  AND COALESCE(lcs.is_active, TRUE) = TRUE
				LIMIT 1
				""");
		UUID activeAccountStatusId = resolveCachedLookupId("client_account_status_Active", """
				SELECT lcas.lu_client_account_status_id
				FROM clients.lu_client_account_status lcas
				WHERE lcas.client_account_status_type = 'Active'
				  AND COALESCE(lcas.is_active, TRUE) = TRUE
				LIMIT 1
				""");

		final String updateClientAgreementSql = """
				UPDATE client_agreements.client_agreement ca
				SET
				    client_agreement_status_id = ?,
				    is_active = TRUE,
				    modified_on = NOW(),
				    modified_by = ?
				FROM transactions.invoice i
				WHERE i.invoice_id = ?
				  AND i.application_id = ?
				  AND i.client_agreement_id IS NOT NULL
				  AND i.client_agreement_id = ca.client_agreement_id
				""";

		cluboneJdbcTemplate.update(updateClientAgreementSql, activeAgreementStatusId, actorId, invoiceId,
				AccessContext.applicationId());

		final String updateClientRoleStatusSql = """
				UPDATE clients.client_role_status crs
				SET
				    agreement_status_id = ?,
				    status_id = ?,
				    account_status_id = ?,
				    is_active = TRUE,
				    modified_on = NOW(),
				    modified_by = ?
				FROM transactions.invoice i
				JOIN client_agreements.client_agreement ca
				  ON ca.client_agreement_id = i.client_agreement_id
				WHERE i.invoice_id = ?
				  AND i.application_id = ?
				  AND i.client_agreement_id IS NOT NULL
				  AND crs.client_role_id = ca.client_role_id
				  AND COALESCE(crs.is_active, TRUE) = TRUE
				""";

		cluboneJdbcTemplate.update(updateClientRoleStatusSql, activeAgreementStatusId, activeClientStatusId,
				activeAccountStatusId, actorId, invoiceId, AccessContext.applicationId());
	}

	@Override
	public Optional<UUID> findClientRoleIdByInvoiceId(UUID invoiceId, UUID applicationId) {
		if (invoiceId == null) {
			return Optional.empty();
		}
		UUID appId = applicationId != null ? applicationId : AccessContext.applicationId();
		try {
			UUID id = cluboneJdbcTemplate.queryForObject("""
					SELECT ca.client_role_id
					FROM transactions.invoice i
					JOIN client_agreements.client_agreement ca
					  ON ca.client_agreement_id = i.client_agreement_id
					WHERE i.invoice_id = ?
					  AND i.application_id = ?
					LIMIT 1
					""", UUID.class, invoiceId, appId);
			return Optional.ofNullable(id);
		} catch (EmptyResultDataAccessException ex) {
			return Optional.empty();
		}
	}

	@Override
	public void refreshClientDashboardProjection(UUID clientRoleId) {
		if (clientRoleId == null) {
			return;
		}
		try {
			cluboneJdbcTemplate.query(
					"SELECT clients.refresh_client_dashboard_proj(?)",
					ps -> ps.setObject(1, clientRoleId),
					rs -> null);
		} catch (DataAccessException ex) {
			logger.warn("refresh_client_dashboard_proj skipped for clientRoleId={}: {}",
					clientRoleId, ex.getMessage());
		}
	}

	private static final class PromotionEffectValueRowMapper implements RowMapper<PromotionEffectValueDTO> {
		@Override
		public PromotionEffectValueDTO mapRow(ResultSet rs, int rowNum) throws SQLException {
			PromotionEffectValueDTO dto = new PromotionEffectValueDTO();
			dto.setPromotionEffectId((UUID) rs.getObject("promotion_effect_id"));
			dto.setEffectTypeId((UUID) rs.getObject("effect_type_id"));
			dto.setEffectTypeDescription(rs.getString("effect_type_description"));
			dto.setValueAmount(rs.getBigDecimal("value_amount"));
			dto.setValuePercent(rs.getBigDecimal("value_percent"));
			return dto;
		}
	}

	/**
	 * NOTE: - Uses promotions.promotion.current_version_id to pull effects from the
	 * CURRENT promotion version. - Assumes promotions.promotion_entity_scope has
	 * promotion_version_id (common in this model). If your column name differs,
	 * update the join accordingly.
	 */
	private static final String SQL_FETCH_EFFECT_VALUES_FOR_CURRENT_VERSION = """
			SELECT
			    pe.promotion_effect_id,
			    pe.effect_type_id,
			    et.name AS effect_type_description,
			    pe.value_amount,
			    pe.value_percent
			FROM promotions.promotion p
			JOIN promotions.promotion_version pv
			    ON pv.promotion_version_id = p.current_version_id
			   AND pv.is_active = true
			JOIN promotions.promotion_entity_scope pes
			    ON pes.promotion_version_id = pv.promotion_version_id
			   AND pes.is_active = true
			JOIN promotions.promotion_effects pe
			    ON pe.promotion_entity_scope_id = pes.promotion_entity_scope_id
			   AND pe.is_active = true
			JOIN promotions.lu_effect_type et
			    ON et.effect_type_id = pe.effect_type_id
			   AND et.is_active = true
			WHERE p.promotion_id = ?
			  AND p.application_id = ?
			  AND p.is_active = true
			ORDER BY pe.display_order ASC
			""";

	@Override
	public List<PromotionEffectValueDTO> fetchEffectValuesByPromotionId(UUID promotionId, UUID applicationId) {
		return cluboneJdbcTemplate.query(SQL_FETCH_EFFECT_VALUES_FOR_CURRENT_VERSION,
				new PromotionEffectValueRowMapper(), promotionId, applicationId);
	}

	@Override
	public boolean isFeeItem(UUID itemId, UUID applicationId) {

	    final String sql = """
	        SELECT 1
	        FROM items.item it
	        JOIN items.lu_item_group ig
	          ON ig.item_group_id = it.item_group_id
	         AND ig.application_id = it.application_id
	        WHERE it.item_id = ?
	          AND it.application_id = ?
	          AND COALESCE(it.is_active, true) = true
	          AND COALESCE(ig.is_active, true) = true
	          AND ig.code = 'FEE'
	        LIMIT 1
	    """;

	    List<Integer> rows = cluboneJdbcTemplate.query(sql, ps -> {
	        ps.setObject(1, itemId);
	        ps.setObject(2, applicationId);
	    }, (rs, rowNum) -> 1);

	    return rows != null && !rows.isEmpty();
	}
	
	public InvoiceSeedRow fetchInvoiceSeed(UUID invoiceId) {
	    final String sql = """
	        select
	          i.invoice_id,
	          i.client_role_id,
	          i.level_id,
	          i.billing_address,
	          i.client_agreement_id,
	          i.created_by
	        from transactions.invoice i
	        where i.invoice_id = ?
	          and i.application_id = ?
	    """;
	    return cluboneJdbcTemplate.queryForObject(sql, (rs, rn) -> new InvoiceSeedRow(
	        rs.getObject("invoice_id", UUID.class),
	        rs.getObject("client_role_id", UUID.class),
	        rs.getObject("level_id", UUID.class),
	        rs.getString("billing_address"),
	        rs.getObject("client_agreement_id", UUID.class),
	        rs.getObject("created_by", UUID.class)
	    ), invoiceId, AccessContext.applicationId());
	}

	public List<InvoiceBillableLineRow> fetchBillableLeafLines(UUID invoiceId, int cycleNumber,UUID clientAgreementId) {

	    final String sql = """
	       WITH ranked AS (
    SELECT
        ie.invoice_entity_id,
        ie.parent_invoice_entity_id,
        ie.entity_type_id,
        ie.entity_id,
        ie.price_plan_template_id,
        bpcb.package_price_cycle_band_id AS price_cycle_band_id,
        ROW_NUMBER() OVER (
            PARTITION BY
                ie.parent_invoice_entity_id,
                ie.entity_type_id,
                ie.entity_id,
                ie.price_plan_template_id
            ORDER BY
                ie.created_on DESC,
                ie.invoice_entity_id DESC
        ) AS rn
    FROM transactions.invoice_entity ie
    JOIN transactions.invoice_entity_price_band iepb
        ON iepb.invoice_entity_id = ie.invoice_entity_id
    JOIN package.package_plan_template_term_config tc
        ON tc.package_plan_template_id = ie.price_plan_template_id
       AND tc.application_id = ie.application_id
       AND COALESCE(tc.is_active, true) = true
    JOIN package.package_price_cycle_band bpcb
        ON bpcb.package_plan_template_term_config_id =
           tc.package_plan_template_term_config_id
       AND bpcb.application_id = ie.application_id
       AND bpcb.cycle_range @> ?::int
    WHERE ie.invoice_id = ?
      AND ie.application_id = ?
      AND ie.client_agreement_id = ?
      AND ie.is_active = true
      AND iepb.is_active = true
      -- AND bpcb.is_active = true
      AND ie.price_plan_template_id IS NOT NULL
)
SELECT
    invoice_entity_id,
    entity_type_id,
    entity_id,
    price_plan_template_id,
    price_cycle_band_id
FROM ranked
WHERE rn = 1;
	    """;

	    return cluboneJdbcTemplate.query(
	        sql,
	        (rs, rn) -> new InvoiceBillableLineRow(
	            rs.getObject("invoice_entity_id", UUID.class),
	            rs.getObject("entity_type_id", UUID.class),
	            rs.getObject("entity_id", UUID.class),
	            rs.getObject("price_plan_template_id", UUID.class),
	            rs.getObject("price_cycle_band_id", UUID.class)
	        ),
	        cycleNumber,
	        invoiceId,
	        AccessContext.applicationId(),
	        clientAgreementId
	    );
	}

	public record CycleBandInfo(
	        UUID packagePriceCycleBandId,
	        BigDecimal unitPrice
	) {}
	
	@Override
    public CycleBandRef resolveCycleBand(UUID packagePlanTemplateId, int cycleNumber) {

        final String sql = """
            select
                b.package_price_cycle_band_id,
                b.unit_price
            from package.package_plan_template_term_config tc
            join package.package_price_cycle_band b
              on b.package_plan_template_term_config_id =
                 tc.package_plan_template_term_config_id
             and b.application_id = tc.application_id
            where tc.package_plan_template_id = ?
              and tc.application_id = ?
              and coalesce(tc.is_active, true) = true
              and b.start_cycle <= ?
              and (b.end_cycle is null or b.end_cycle >= ?)
            order by tc.created_on desc nulls last,
                     b.start_cycle desc
            limit 1
        """;

        List<CycleBandRef> rows = cluboneJdbcTemplate.query(
            sql,
            new Object[]{
                packagePlanTemplateId,
                AccessContext.applicationId(),
                cycleNumber,
                cycleNumber
            },
            (rs, rn) -> new CycleBandRef(
                (UUID) rs.getObject("package_price_cycle_band_id"),
                rs.getBigDecimal("unit_price")
            )
        );

        return rows.isEmpty() ? null : rows.get(0);
    }
	public record InvoiceBillableLineRow(
		    UUID invoiceEntityId,
		    UUID entityTypeId,
		    UUID entityId,
		    UUID pricePlanTemplateId,
		    UUID oldPriceCycleBandId
		) {}
	@Override
	public BigDecimal findUnitPriceByCycleBandId(UUID newBandId) {
		if (newBandId == null) {
			return null;
		}

		final String sql = """
				SELECT unit_price
				FROM package.package_price_cycle_band
				WHERE package_price_cycle_band_id = ?
				  AND application_id = ?
				LIMIT 1
				""";

		return cluboneJdbcTemplate.query(
				sql,
				rs -> rs.next() ? rs.getBigDecimal("unit_price") : null,
				newBandId,
				AccessContext.applicationId()
		);
	}


	@Override
	public int resolveDefaultQtyFromEntitlement(UUID planTemplateId, UUID applicationId) {
	    if (planTemplateId == null) return 1;
	    UUID appId = applicationId != null ? applicationId : AccessContext.applicationId();

	    // Entitlement rows hang off package_plan_template_term_config, not package_plan_template directly.
	    final String sql = """
	        select coalesce(e.quantity_per_cycle, 1) as qty
	        from package.package_plan_template_entitlement e
	        join package.package_plan_template_term_config tc
	          on tc.package_plan_template_term_config_id = e.package_plan_template_term_config_id
	        where tc.package_plan_template_id = ?
	          and coalesce(tc.is_active, true) = true
	          and e.application_id = ?
	        order by e.created_on desc
	        limit 1
	    """;

	    Integer q = cluboneJdbcTemplate.query(
	            sql,
	            rs -> rs.next() ? rs.getInt("qty") : 1,
	            planTemplateId,
	            appId);

	    return (q == null || q <= 0) ? 1 : q;
	}
	@Override
	public UUID findBillingDayRuleIdForPlanTemplate(UUID planTemplateId) {
	    if (planTemplateId == null) return null;

	    final String sql = """
	        select tc.subscription_billing_day_rule_id
	        from package.package_plan_template_term_config tc
	        where tc.package_plan_template_id = ?
	          and coalesce(tc.is_active, true) = true
	        order by tc.created_on desc nulls last, tc.package_plan_template_term_config_id
	        limit 1
	    """;

	    return cluboneJdbcTemplate.query(sql, rs -> {
	        if (rs.next()) return (UUID) rs.getObject(1);
	        return null;
	    }, planTemplateId);
	}
	@Override
	public Integer findBillingDayOfMonth(UUID billingDayRuleId) {
	    if (billingDayRuleId == null) return null;

	    final List<String> candidates = List.of(
	        "select billing_day from billing_config.subscription_billing_day_rule where subscription_billing_day_rule_id = ? and coalesce(is_active, true) = true",
	        "select billing_day_of_month from billing_config.subscription_billing_day_rule where subscription_billing_day_rule_id = ? and coalesce(is_active, true) = true"
	    );

	    for (String sql : candidates) {
	        try {
	            Integer v = cluboneJdbcTemplate.query(sql, rs -> {
	                if (rs.next()) return rs.getInt(1);
	                return null;
	            }, billingDayRuleId);

	            if (v != null && v >= 1 && v <= 31) return v;
	        } catch (Exception ignore) {
	            // try next candidate
	        }
	    }
	    return null;
	}

	@Override
	public void saveInvoiceEntityPromotions(List<InvoiceEntityDTO> lines, UUID actorId) {

	    if (lines == null || lines.isEmpty()) return;

	    record Row(UUID invoiceEntityId, InvoiceEntityPromotionDTO p) {}

	    List<Row> rows = new ArrayList<>();

	    for (InvoiceEntityDTO line : lines) {
	        if (line == null || line.getInvoiceEntityId() == null) continue;

	        var promos = line.getPromotions();
	        if (promos == null || promos.isEmpty()) continue;

	        for (InvoiceEntityPromotionDTO p : promos) {
	            if (p == null) continue;
	            if (p.getPromotionVersionId() == null) continue;
	            if (p.getPromotionApplicabilityId() == null) continue;
	            if (p.getPromotionEffectId() == null) continue;
	            if (p.getPromotionAmount() == null || p.getPromotionAmount().compareTo(BigDecimal.ZERO) <= 0) continue;

	            rows.add(new Row(line.getInvoiceEntityId(), p));
	        }
	    }

	    if (rows.isEmpty()) return;

	    final String sql = """
	        INSERT INTO transactions.invoice_entity_promotion
	        (
	            invoice_entity_promotion_id,
	            invoice_entity_id,
	            promotion_version_id,
	            promotion_amount,
	            is_active,
	            created_on,
	            created_by,
	            modified_on,
	            modified_by,
	            promotion_applicability_id,
	            promotion_effect_id
	        )
	        VALUES (?, ?, ?, ?, true, now(), ?, now(), ?, ?, ?)
	        """;

	    cluboneJdbcTemplate.batchUpdate(sql, rows, 500, (ps, r) -> {
	        ps.setObject(1, UUID.randomUUID());
	        ps.setObject(2, r.invoiceEntityId());
	        ps.setObject(3, r.p().getPromotionVersionId());
	        ps.setBigDecimal(4, r.p().getPromotionAmount());

	        ps.setObject(5, actorId);
	        ps.setObject(6, actorId);

	        ps.setObject(7, r.p().getPromotionApplicabilityId());
	        ps.setObject(8, r.p().getPromotionEffectId());
	    });
	}

	public Optional<UUID> findPromotionIdAppliedOnInvoice(UUID invoiceId) {
	    String sql = """
	        SELECT DISTINCT pv.promotion_id
	        FROM transactions.invoice_entity_promotion iep
	        JOIN promotions.promotion_version pv
	          ON pv.promotion_version_id = iep.promotion_version_id
	        JOIN transactions.invoice_entity ie
	          ON ie.invoice_entity_id = iep.invoice_entity_id
	        WHERE ie.invoice_id = ?
	          AND ie.application_id = ?
	          AND iep.is_active = true
	        LIMIT 1
	    """;
	    List<UUID> rows = cluboneJdbcTemplate.query(sql, (rs, i) -> rs.getObject(1, UUID.class), invoiceId,
	            AccessContext.applicationId());
	    return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
	}

	@Override
	public String findFrequencyNameForPlanTemplate(UUID planTemplateId) {
	    if (planTemplateId == null) return null;

	    return cluboneJdbcTemplate.query(
	        """
	        SELECT COALESCE(bpu.code, bpu.display_name) AS frequency_name
	        FROM package.package_plan_template bpt
	        JOIN package.lu_plan_template lpt
	          ON lpt.lu_plan_template_id = bpt.lu_plan_template_id
	        LEFT JOIN billing_config.billing_period_unit bpu
	          ON bpu.billing_period_unit_id = lpt.billing_period_unit_id
	        WHERE bpt.package_plan_template_id = ?
	          AND COALESCE(bpt.is_active, true) = true
	        LIMIT 1
	        """,
	        rs -> rs.next() ? rs.getString(1) : null,
	        planTemplateId
	    );
	}

	@Override
	public Integer findIntervalCountForPlanTemplate(UUID planTemplateId) {
	    if (planTemplateId == null) return null;

	    return cluboneJdbcTemplate.query(
	        """
	        SELECT COALESCE(tc.interval_count, 1) AS interval_count
	        FROM package.package_plan_template_term_config tc
	        WHERE tc.package_plan_template_id = ?
	          AND COALESCE(tc.is_active, true) = true
	        ORDER BY tc.created_on DESC NULLS LAST, tc.package_plan_template_term_config_id
	        LIMIT 1
	        """,
	        rs -> rs.next() ? rs.getObject("interval_count", Integer.class) : null,
	        planTemplateId
	    );
	}

	@Override
	public String findBillingDayText(UUID billingDayRuleId) {
	    if (billingDayRuleId == null) return null;

	    return cluboneJdbcTemplate.query(
	        """
	        SELECT billing_day
	        FROM billing_config.subscription_billing_day_rule
	        WHERE subscription_billing_day_rule_id = ?
	          AND COALESCE(is_active, true) = true
	        LIMIT 1
	        """,
	        rs -> rs.next() ? rs.getString(1) : null,
	        billingDayRuleId
	    );
	}

	@Override
	public Optional<UUID> findBillingCollectionTypeIdByCode(String code) {
		if (code == null || code.isBlank()) {
			return Optional.empty();
		}
		try {
			UUID id = cluboneJdbcTemplate.queryForObject("""
					SELECT billing_collection_type_id
					FROM transactions.lu_billing_collection_type
					WHERE UPPER(TRIM(code)) = UPPER(TRIM(?))
					  AND COALESCE(is_active, true) = true
					LIMIT 1
					""", UUID.class, code);
			return Optional.ofNullable(id);
		} catch (EmptyResultDataAccessException e) {
			return Optional.empty();
		}
	}

	@Override
	public Optional<UUID> findFirstActiveBillingCollectionTypeId() {
		try {
			UUID id = cluboneJdbcTemplate.queryForObject("""
					SELECT billing_collection_type_id
					FROM transactions.lu_billing_collection_type
					WHERE COALESCE(is_active, true) = true
					ORDER BY sort_order NULLS LAST, code
					LIMIT 1
					""", UUID.class);
			return Optional.ofNullable(id);
		} catch (EmptyResultDataAccessException e) {
			return Optional.empty();
		}
	}

	@Override
	public Optional<UUID> findCurrentAgreementVersionId(UUID agreementId, LocalDate asOf) {
		if (agreementId == null || asOf == null) {
			return Optional.empty();
		}
		try {
			UUID id = cluboneJdbcTemplate.queryForObject("""
					SELECT av.agreement_version_id
					FROM agreements.agreement_version av
					WHERE av.agreement_id = ?
					  AND COALESCE(av.is_active, true) = true
					  AND COALESCE(av.is_published, true) = true
					  AND CAST(av.valid_from AS date) <= CAST(? AS date)
					  AND (av.valid_to IS NULL OR CAST(av.valid_to AS date) >= CAST(? AS date))
					ORDER BY (av.is_current IS TRUE) DESC, av.valid_from DESC
					LIMIT 1
		""", UUID.class, agreementId, asOf, asOf);
			return Optional.ofNullable(id);
		} catch (EmptyResultDataAccessException e) {
			return Optional.empty();
		}
	}

	@Override
	public Optional<UUID> findChargeLineKindIdByCode(String code) {
		if (code == null || code.isBlank()) {
			return Optional.empty();
		}
		try {
			UUID id = cluboneJdbcTemplate.queryForObject("""
					SELECT charge_line_kind_id
					FROM transactions.lu_charge_line_kind
					WHERE UPPER(TRIM(code)) = UPPER(TRIM(?))
					  AND COALESCE(is_active, true) = true
					LIMIT 1
					""", UUID.class, code);
			return Optional.ofNullable(id);
		} catch (EmptyResultDataAccessException e) {
			return Optional.empty();
		}
	}

	@Override
	public Optional<UUID> resolveLevelIdForInvoice(UUID levelIdOrReferenceEntityId) {
		if (levelIdOrReferenceEntityId == null) {
			return Optional.empty();
		}
		try {
			UUID byPk = cluboneJdbcTemplate.queryForObject(
					"SELECT level_id FROM locations.levels WHERE level_id = ? LIMIT 1",
					UUID.class,
					levelIdOrReferenceEntityId);
			return Optional.ofNullable(byPk);
		} catch (EmptyResultDataAccessException ignored) {
			// not a level_id â€” try reference_entity_id
		}
		try {
			UUID byRef = cluboneJdbcTemplate.queryForObject(
					"SELECT level_id FROM locations.levels WHERE reference_entity_id = ? LIMIT 1",
					UUID.class,
					levelIdOrReferenceEntityId);
			return Optional.ofNullable(byRef);
		} catch (EmptyResultDataAccessException e) {
			return Optional.empty();
		}
	}

	@Override
	public List<InvoiceTransactionDetailDTO> findInvoiceTransactions(
	        UUID invoiceId
	) {
	    final String sql = """
	            SELECT
	                t.transaction_id,
	                t.transaction_number,

	                cpt.client_payment_transaction_id,
	                cpt.client_payment_transaction_number,
	                cpt.client_payment_intent_id,

	                pis.payment_intent_status,

	                pg.name AS gateway_name,

	                cpt.payment_gateway_payment_id,
	                cpt.payment_gateway_order_id,

	                pgts.status_code AS gateway_status_code,

	                pgmt.method_type_code,
	                pgmt.method_type_name,

	                pgpt.payment_type_code,
	                pgpt.payment_type_name,

	                cpm.card_last4,
	                cpm.card_type,
	                cpm.card_network,

	                ROUND(
	                    COALESCE(cpt.amount, 0)::numeric / 100.0,
	                    2
	                ) AS amount,

	                cpt.failure_reason,
	                cpt.created_on

	            FROM transactions."transaction" t

	            JOIN client_payments.client_payment_transaction cpt
	              ON cpt.client_payment_transaction_id =
	                 t.client_payment_transaction_id
	             AND cpt.application_id =
	                 t.application_id

	            LEFT JOIN client_payments.client_payment_intent cpi
	              ON cpi.client_payment_intent_id =
	                 cpt.client_payment_intent_id
	             AND cpi.application_id =
	                 t.application_id

	            LEFT JOIN client_payments.lu_payment_intent_status pis
	              ON pis.payment_intent_status_id =
	                 cpi.intent_status_id

	            LEFT JOIN client_payments.client_payment_method cpm
	              ON cpm.client_payment_method_id =
	                 cpt.client_payment_method_id
	             AND cpm.application_id =
	                 t.application_id

	            LEFT JOIN payment_gateway.payment_gateway pg
	              ON pg.payment_gateway_id =
	                 cpm.payment_gateway_id

	            LEFT JOIN payment_gateway.lu_payment_gateway_transaction_status pgts
	              ON pgts.payment_gateway_transaction_status_id =
	                 cpt.payment_gateway_transaction_status_id

	            LEFT JOIN payment_gateway.lu_payment_gateway_method_type pgmt
	              ON pgmt.payment_gateway_method_type_id =
	                 cpm.payment_gateway_method_type_id

	            LEFT JOIN payment_gateway.lu_payment_gateway_payment_type pgpt
	              ON pgpt.payment_gateway_payment_type_id =
	                 cpt.payment_type_id

	            WHERE t.invoice_id = ?
	              AND t.application_id = ?
	              AND COALESCE(t.is_active, true) = true

	            ORDER BY cpt.created_on DESC NULLS LAST
	            """;

	    return cluboneJdbcTemplate.query(
	            sql,
	            (rs, rowNum) -> new InvoiceTransactionDetailDTO(
	                    rs.getObject("transaction_id", UUID.class),
	                    rs.getString("transaction_number"),

	                    rs.getObject(
	                            "client_payment_transaction_id",
	                            UUID.class
	                    ),
	                    rs.getString("client_payment_transaction_number"),

	                    rs.getObject(
	                            "client_payment_intent_id",
	                            UUID.class
	                    ),
	                    rs.getString("payment_intent_status"),

	                    rs.getString("gateway_name"),
	                    rs.getString("payment_gateway_payment_id"),
	                    rs.getString("payment_gateway_order_id"),
	                    rs.getString("gateway_status_code"),

	                    rs.getString("method_type_code"),
	                    rs.getString("method_type_name"),

	                    rs.getString("payment_type_code"),
	                    rs.getString("payment_type_name"),

	                    rs.getString("card_last4"),
	                    rs.getString("card_type"),
	                    rs.getString("card_network"),

	                    rs.getBigDecimal("amount"),
	                    rs.getString("failure_reason"),

	                    rs.getObject(
	                            "created_on",
	                            OffsetDateTime.class
	                    )
	            ),
	            invoiceId,
	            AccessContext.applicationId()
	    );
	}
	
	@Override
	public List<InvoiceRefundDetailDTO> findInvoiceRefunds(
	        UUID invoiceId
	) {
	    final String sql = """
	            SELECT
	                r.client_payment_refund_id,
	                r.client_payment_transaction_id,
	                r.invoice_id,

	                ROUND(
	                    COALESCE(r.refund_amount, 0)::numeric / 100.0,
	                    2
	                ) AS refund_amount,

	                r.currency_code,
	                pg.name AS gateway_name,
	                pgmt.method_type_name,

	                r.refund_status_code,
	                r.refund_reason_code,
	                r.comments,
	                r.failure_reason,
	                r.idempotency_key,
	                r.gateway_refund_id,
	                r.webhook_reconciled,
	                r.webhook_reconciled_on,
	                r.created_on

	            FROM client_payments.client_payment_refund r

	            LEFT JOIN payment_gateway.payment_gateway pg
	              ON pg.payment_gateway_id =
	                 r.payment_gateway_id

	            LEFT JOIN payment_gateway.lu_payment_gateway_method_type pgmt
	              ON pgmt.payment_gateway_method_type_id =
	                 r.payment_gateway_method_type_id

	            WHERE r.invoice_id = ?
	              AND r.application_id = ?

	            ORDER BY r.created_on DESC
	            """;

	    return cluboneJdbcTemplate.query(
	            sql,
	            (rs, rowNum) -> new InvoiceRefundDetailDTO(
	                    rs.getObject(
	                            "client_payment_refund_id",
	                            UUID.class
	                    ),
	                    rs.getObject(
	                            "client_payment_transaction_id",
	                            UUID.class
	                    ),
	                    rs.getObject("invoice_id", UUID.class),
	                    rs.getBigDecimal("refund_amount"),
	                    rs.getString("currency_code"),
	                    rs.getString("gateway_name"),
	                    rs.getString("method_type_name"),
	                    rs.getString("refund_status_code"),
	                    rs.getString("refund_reason_code"),
	                    rs.getString("comments"),
	                    rs.getString("failure_reason"),
	                    rs.getString("idempotency_key"),
	                    rs.getString("gateway_refund_id"),
	                    rs.getBoolean("webhook_reconciled"),
	                    rs.getObject(
	                            "webhook_reconciled_on",
	                            OffsetDateTime.class
	                    ),
	                    rs.getObject(
	                            "created_on",
	                            OffsetDateTime.class
	                    )
	            ),
	            invoiceId,
	            AccessContext.applicationId()
	    );
	}
	
	@Override
	public List<InvoiceRefundAllocationDTO> findInvoiceRefundAllocations(
	        UUID invoiceId
	) {
	    final String sql = """
	            SELECT
	                ra.client_payment_refund_allocation_id,
	                ra.client_payment_refund_id,
	                ra.invoice_id,
	                ROUND(
	                    COALESCE(ra.allocated_amount, 0)::numeric / 100.0,
	                    2
	                ) AS allocated_amount,
	                ra.created_on
	            FROM client_payments.client_payment_refund_allocation ra
	            WHERE ra.invoice_id = ?
	              AND ra.application_id = ?
	            ORDER BY ra.created_on DESC
	            """;

	    return cluboneJdbcTemplate.query(
	            sql,
	            (rs, rowNum) -> new InvoiceRefundAllocationDTO(
	                    rs.getObject(
	                            "client_payment_refund_allocation_id",
	                            UUID.class
	                    ),
	                    rs.getObject(
	                            "client_payment_refund_id",
	                            UUID.class
	                    ),
	                    rs.getObject("invoice_id", UUID.class),
	                    rs.getBigDecimal("allocated_amount"),
	                    rs.getObject("created_on", OffsetDateTime.class)
	            ),
	            invoiceId,
	            AccessContext.applicationId()
	    );
	}
	
	@Override
	public List<InvoiceAdjustmentDetailDTO> findInvoiceAdjustments(
	        UUID invoiceId
	) {
	    final String sql = """
	            SELECT DISTINCT
	                bsa.billing_schedule_adjustment_id,
	                bsa.billing_schedule_id,

	                bsa.billing_adjustment_type_id::text
	                    AS adjustment_type_code,

	                NULL::text AS adjustment_type_name,
	                NULL::text AS sign_behavior,

	                COALESCE(bsa.amount, 0) AS amount,

	                COALESCE(
	                    bsa.is_system_generated,
	                    false
	                ) AS system_generated,

	                bsa.reference_entity_type_id::text
	                    AS reference_entity_type,

	                bsa.reference_entity_id,
	                bsa.notes,

	                COALESCE(
	                    bsa.is_active,
	                    true
	                ) AS active,

	                bsa.created_on,
	                bsa.reversed_on,
	                bsa.reversal_reason

	            FROM transactions.invoice_entity ie

	            JOIN client_subscription_billing.subscription_billing_schedule sbs
	              ON sbs.billing_schedule_id =
	                 ie.billing_schedule_id
	             AND sbs.application_id =
	                 ie.application_id

	            JOIN client_subscription_billing
	                    .subscription_billing_schedule_adjustment bsa
	              ON bsa.billing_schedule_id =
	                 sbs.billing_schedule_id

	            WHERE ie.invoice_id = ?
	              AND ie.application_id = ?
	              AND COALESCE(ie.is_active, true) = true

	            ORDER BY bsa.created_on DESC
	            """;

	    return cluboneJdbcTemplate.query(
	            sql,
	            (rs, rowNum) -> new InvoiceAdjustmentDetailDTO(
	                    rs.getObject(
	                            "billing_schedule_adjustment_id",
	                            UUID.class
	                    ),
	                    rs.getObject(
	                            "billing_schedule_id",
	                            UUID.class
	                    ),
	                    rs.getString("adjustment_type_code"),
	                    rs.getString("adjustment_type_name"),
	                    rs.getString("sign_behavior"),
	                    rs.getBigDecimal("amount"),
	                    rs.getBoolean("system_generated"),
	                    rs.getString("reference_entity_type"),
	                    rs.getObject(
	                            "reference_entity_id",
	                            UUID.class
	                    ),
	                    rs.getString("notes"),
	                    rs.getBoolean("active"),
	                    rs.getObject(
	                            "created_on",
	                            OffsetDateTime.class
	                    ),
	                    rs.getObject(
	                            "reversed_on",
	                            OffsetDateTime.class
	                    ),
	                    rs.getString("reversal_reason")
	            ),
	            invoiceId,
	            AccessContext.applicationId()
	    );
	}
}