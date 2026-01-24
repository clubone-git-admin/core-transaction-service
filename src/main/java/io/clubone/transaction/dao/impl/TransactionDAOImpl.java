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
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
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

import io.clubone.transaction.dao.TransactionDAO;
import io.clubone.transaction.util.FrequencyUnit;
import io.clubone.transaction.v2.vo.BundlePriceCycleBandDTO;
import io.clubone.transaction.v2.vo.CalculationMode;
import io.clubone.transaction.v2.vo.CycleBandRef;
import io.clubone.transaction.v2.vo.DiscountDetailDTO;
import io.clubone.transaction.v2.vo.InvoiceDetailDTO;
import io.clubone.transaction.v2.vo.InvoiceDetailRaw;
import io.clubone.transaction.v2.vo.InvoiceEntityDiscountDTO;
import io.clubone.transaction.v2.vo.InvoiceEntityPriceBandDTO;
import io.clubone.transaction.v2.vo.InvoiceEntityPromotionDTO;
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
			    i.level_id
			FROM "transactions".invoice i
			WHERE i.invoice_id = ?
			""";

	private static final String UPDATE_TRANSACTION_CLIENT_AGREEMENT_SQL = """
			UPDATE "transactions"."transaction"
			SET client_agreement_id = ?
			WHERE transaction_id = ?
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
			  AND tr.level_id = '9dcc30ea-e4cd-49b4-8e47-3e9bd8a5de0e'
			""";

	private static final String BUNDLE_PRICE_BAND_SQL = "SELECT unit_price, down_payment_units FROM package.package_price_cycle_band WHERE package_price_cycle_band_id = ?";

	private static final String SQL_TYPE_NAME_BY_BUNDLE_ITEM_ID = """
			    SELECT lit.type_name
			    FROM bundles_new.bundle_item bi
			    JOIN items.item it          ON it.item_id = bi.item_id
			    JOIN items.lu_itemtypes lit ON lit.item_type_id = it.item_type_id
			    WHERE bi.bundle_item_id = ?
			      AND COALESCE(bi.is_active, true) = true
			    LIMIT 1
			""";

	private static final String SQL_IS_PRORATE_APPLICABLE = """
			    SELECT EXISTS (
			        SELECT 1
			        FROM bundles_new.bundle_plan_template          bpt
			        JOIN bundles_new.lu_proration_strategy         ps  ON ps.proration_strategy_id = bpt.default_proration_strategy_id
			        JOIN client_subscription_billing.lu_subscription_frequency sf
			                                                          ON sf.subscription_frequency_id = bpt.subscription_frequency_id
			        WHERE bpt.plan_template_id = ?
			          AND COALESCE(bpt.is_active, true) = true
			          AND COALESCE(ps.is_active,  true) = true
			          AND COALESCE(sf.is_active,  true) = true
			          AND UPPER(ps.code) = 'DAILY'
			          AND UPPER(sf.frequency_name) = 'MONTHLY'
			    );
			""";

	private static final String SQL_INVOICE_SUMMARY = """
			WITH pay AS (
			    SELECT t.invoice_id, COALESCE(SUM(cpt.amount)::numeric(10,2), 0)::numeric(10,2) AS paid_amount
			    FROM "transactions"."transaction" t
			    JOIN client_payments.client_payment_transaction cpt
			      ON cpt.client_payment_transaction_id = t.client_payment_transaction_id
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
			  AND COALESCE(i.is_active, true) = true
			ORDER BY i.invoice_date DESC, i.invoice_number
			LIMIT COALESCE(?, 100)
			OFFSET COALESCE(?, 0)
			""";

	@Override
	public UUID saveInvoice(InvoiceDTO dto) {
		UUID invoiceId = UUID.randomUUID();
		String sql = """
				INSERT INTO transactions.invoice (
				    invoice_id, invoice_number, invoice_date, client_role_id, billing_address,
				    invoice_status_id, total_amount, sub_total, tax_amount, discount_amount,
				    is_paid, created_on
				) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, NOW())
				""";

		cluboneJdbcTemplate.update(sql, invoiceId, dto.getInvoiceNumber(), dto.getInvoiceDate(), dto.getClientRoleId(),
				dto.getBillingAddress(), dto.getInvoiceStatusId(), dto.getTotalAmount(), dto.getSubTotal(),
				dto.getTaxAmount(), dto.getDiscountAmount(), dto.isPaid());

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
				    created_on
				) VALUES (
				    ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, NOW()
				);
								""";

		// If your invoice table has created_by, pass dto.getCreatedBy() as the last
		// param and uncomment above columns.
		cluboneJdbcTemplate.update(insertInvoiceSql, invoiceId, dto.getInvoiceNumber(), dto.getInvoiceDate(),
				dto.getClientRoleId(), dto.getLevelId(), dto.getBillingAddress(), dto.getInvoiceStatusId(), totalSum, // use
																														// computed
				// canonical totals
				subtotal, taxSum, discountSum, Boolean.TRUE.equals(dto.isPaid()), dto.getClientAgreementId()
		/* , dto.getCreatedBy() */
		);

		// 2) Resolve BUNDLE type id once (for parent/child grouping)
		UUID bundleTypeId = cluboneJdbcTemplate.queryForObject(
				"SELECT entity_type_id FROM transactions.lu_entity_type WHERE LOWER(entity_type) = LOWER('BUNDLE')",
				UUID.class);

		UUID lastBundleHeaderId = null;

		// 3) Insert invoice entities
		final String insertEntitySql = """
				INSERT INTO transactions.invoice_entity (
				    invoice_entity_id, parent_invoice_entity_id, invoice_id,
				    entity_type_id, entity_id, entity_description,
				    quantity, unit_price, discount_amount, tax_amount, total_amount,
				    created_on, created_by,price_plan_template_id,contract_start_date,client_agreement_id
				) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, NOW(), ?,?,?,?)
				""";

		// 4) Insert taxes per entity
		final String insertTaxSql = """
				INSERT INTO transactions.invoice_entity_tax (
				    invoice_entity_tax_id, invoice_entity_id, tax_rate_id, tax_rate_percentage, tax_amount,
				    created_on, created_by,tax_rate_allocation_id
				) VALUES (?, ?, ?, ?, ?, NOW(), ?,?)
				""";

		// [NEW] 5) Insert discounts per entity
		final String insertDiscountSql = """
				INSERT INTO transactions.invoice_entity_discount (
				    invoice_entity_discount_id, invoice_entity_id, discount_id, discount_amount,
				    adjustment_type_id, calculation_type_id, is_active, created_on, created_by
				) VALUES (?, ?, ?, ?, ?, ?, true, NOW(), ?)
				""";

		// 5b) Insert price bands per entity (after invoice_entity insert)
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

		for (InvoiceEntityDTO li : dto.getLineItems()) {
			UUID ieId = null;
			if (li.getInvoiceEntityId() != null) {
				ieId = li.getInvoiceEntityId();
			} else {
				ieId = UUID.randomUUID();				
			}
			li.setInvoiceEntityId(ieId);

			// Attach to last bundle header if not explicitly given and this isn’t a bundle
			// header
			UUID parentId = li.getParentInvoiceEntityId();
			boolean isBundleHeader = bundleTypeId.equals(li.getEntityTypeId());
			System.out.println("isBundleHeader " + isBundleHeader);
			System.out.println("lastBundleHeaderId " + lastBundleHeaderId);
			/*
			 * if (parentId == null) { if (lastBundleHeaderId != null && !isBundleHeader) {
			 * parentId = lastBundleHeaderId; } } else { // Setting null for item purchase
			 * parentId = null; }
			 */

			cluboneJdbcTemplate.update(insertEntitySql, ieId, parentId, invoiceId, li.getEntityTypeId(),
					li.getEntityId(), li.getEntityDescription(), li.getQuantity(), li.getUnitPrice(),
					li.getDiscountAmount(), li.getTaxAmount(), li.getTotalAmount(), // ensure this equals (qty*unit -
																					// discount + tax) rounded to 2
					dto.getCreatedBy(), li.getPricePlanTemplateId(), li.getContractStartDate(),li.getClientAgreementId()// if you don’t store
																								// created_by, replace
																								// with null and drop
																								// the column
			);

			if (isBundleHeader) {
				lastBundleHeaderId = ieId;
			}

			if (li.getTaxes() != null && !li.getTaxes().isEmpty()) {
				for (InvoiceEntityTaxDTO t : li.getTaxes()) {
					UUID ietId = UUID.randomUUID();
					cluboneJdbcTemplate.update(insertTaxSql, ietId, ieId, t.getTaxRateId(), t.getTaxRate(),
							t.getTaxAmount(), dto.getCreatedBy(), t.getTaxRateAllocationId());
				}
			}

			// [NEW] Insert discounts (each applied discount row)
			if (li.getDiscounts() != null && !li.getDiscounts().isEmpty()) {
				for (InvoiceEntityDiscountDTO d : li.getDiscounts()) {
					UUID iedId = UUID.randomUUID();

					// Defensive: ensure mandatory FKs are present
					UUID discountId = d.getDiscountId();
					BigDecimal discountAmt = d.getDiscountAmount() == null ? BigDecimal.ZERO : d.getDiscountAmount();
					UUID adjustmentTypeId = d.getAdjustmentTypeId(); // must exist in discount.lu_adjustment_type
					UUID calculationTypeId = d.getCalculationTypeId(); // must exist in discount.lu_calculation_type

					// You can add validations here if needed:
					// Objects.requireNonNull(discountId, "discountId is required on
					// InvoiceEntityDiscountDTO");

					cluboneJdbcTemplate.update(insertDiscountSql, iedId, ieId, discountId, discountAmt,
							adjustmentTypeId, calculationTypeId, dto.getCreatedBy());
				}
			}

			// [NEW] Insert price bands tied to this invoice_entity line
			if (li.getPriceBands() != null && !li.getPriceBands().isEmpty()) {
				for (InvoiceEntityPriceBandDTO pb : li.getPriceBands()) {
					UUID iepbId = UUID.randomUUID();
					UUID priceCycleBandId = pb.getPriceCycleBandId(); // must not be null
					BigDecimal unitPrice = pb.getUnitPrice() == null ? BigDecimal.ZERO : pb.getUnitPrice();
					Boolean overridden = Boolean.TRUE.equals(pb.getIsPriceOverridden());

					cluboneJdbcTemplate.update(insertPriceBandSql, iepbId, // invoice_entity_price_band_id
							ieId, // invoice_entity_id (this line’s id)
							priceCycleBandId, // price_cycle_band_id
							unitPrice, // unit_price (numeric(12,3))
							overridden, // is_price_overridden
							dto.getCreatedBy(), // created_by
							dto.getCreatedBy() // modified_by (you can use same user)
					);
				}
			}			

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

		// 1) Insert transaction header (now also sets created_by)
		String sql = """
				    INSERT INTO transactions.transaction (
				        transaction_id, client_agreement_id, client_payment_transaction_id,
				        level_id, invoice_id, transaction_date, created_on, created_by
				    ) VALUES (?, ?, ?, ?, ?, ?, NOW(), ?)
				""";

		cluboneJdbcTemplate.update(sql, transactionId, dto.getClientAgreementId(), dto.getClientPaymentTransactionId(),
				dto.getLevelId(), dto.getInvoiceId(), dto.getTransactionDate(), dto.getCreatedBy() // <-- added
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

			// 3) Taxes (null-safe) — also include created_by
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
				"SELECT invoice_number FROM transactions.invoice WHERE invoice_id = ?", String.class, invoiceId);
	}

	@Override
	public UUID findTransactionIdByInvoiceId(UUID invoiceId) {
		List<UUID> ids = cluboneJdbcTemplate.query("""
				    SELECT transaction_id FROM transactions.transaction WHERE invoice_id = ?
				""", (rs, rn) -> UUID.fromString(rs.getString(1)), invoiceId);
		return ids.isEmpty() ? null : ids.get(0);
	}

	@Override
	public UUID findClientPaymentTxnIdByTransactionId(UUID transactionId) {
		return cluboneJdbcTemplate.queryForObject("""
				    SELECT client_payment_transaction_id
				      FROM transactions.transaction
				     WHERE transaction_id = ?
				""", UUID.class, transactionId);
	}

	@Override
	public UUID findInvoiceStatusIdByName(String statusName) {
		return cluboneJdbcTemplate.queryForObject("""
				    SELECT invoice_status_id
				      FROM transactions.lu_invoice_status
				     WHERE status_name = ?
				""", UUID.class, statusName);
	}

	@Override
	public void updateInvoiceStatusAndPaidFlag(UUID invoiceId, UUID statusId, boolean paid, UUID modifiedBy) {
		cluboneJdbcTemplate.update("""
				    UPDATE transactions.invoice
				       SET invoice_status_id = ?, is_paid = ?, modified_on = NOW(), modified_by = ?
				     WHERE invoice_id = ?
				""", statusId, paid, modifiedBy, invoiceId);
	}

	@Override
	public String currentInvoiceStatusName(UUID invoiceId) {
		return cluboneJdbcTemplate.queryForObject("""
				    SELECT s.status_name
				      FROM transactions.invoice i
				      JOIN transactions.lu_invoice_status s ON s.invoice_status_id = i.invoice_status_id
				     WHERE i.invoice_id = ?
				""", String.class, invoiceId);
	}

	@Override
	public List<BundleComponent> findBundleComponents(UUID bundleId) {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public UUID findEntityTypeIdByName(String name) {
		System.out.println("name");
		String sql = """
				    SELECT entity_type_id
				    FROM transactions.lu_entity_type
				    WHERE LOWER(entity_type) = LOWER(?)
				      AND is_active = true
				    LIMIT 1
				""";
		return cluboneJdbcTemplate.queryForObject(sql, UUID.class, name);
	}

	private static final String SQL = """
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
			SELECT DISTINCT
			  b.description,
			  bi.item_id,
			  i.item_description,
			  bi.item_quantity,
			  ip.price AS "itemPrice",
			  i.tax_group_id,
			  eff_bp.price,
			  biro.is_continuous,
			  birr.recurrence_count,
			  eff_bp.price_level_id
			FROM bundles_new.bundle b
			JOIN bundles_new.bundle_location bl
			  ON bl.bundle_id = b.bundle_id
			JOIN bundles_new.bundle_item bi
			  ON bi.bundle_id = b.bundle_id
			LEFT JOIN bundles_new.bundle_item_recurring_option biro
			  ON biro.bundle_item_id = bi.bundle_item_id
			LEFT JOIN bundles_new.bundle_item_recurring_recurrence birr
			  ON birr.recurring_option_id = biro.recurring_option_id

			LEFT JOIN LATERAL (
			  SELECT bp.price,
			         blp.level_id AS price_level_id
			  FROM bundles_new.bundle_price bp
			 JOIN bundles_new.bundle_location blp
			    ON blp.bundle_location_id = bp.bundle_location_id
			   AND blp.bundle_id = b.bundle_id           -- keep it within this bundle
			  JOIN level_candidates lc
			    ON (blp.level_id = lc.level_id) OR (blp.level_id IS NULL AND lc.level_id IS NULL)
			  WHERE bp.bundle_item_id = bi.bundle_item_id
			  ORDER BY lc.depth ASC
			  LIMIT 1
			) eff_bp ON TRUE

			JOIN items.item i
			  ON i.item_id = bi.item_id
			JOIN items.item_price ip
			  ON i.item_id = i.item_id

			WHERE b.bundle_id = ?
			  AND bl.level_id IN (SELECT level_id FROM ancestors);
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
		return cluboneJdbcTemplate.query(SQL, ROW_MAPPER, levelId, bundleId);
	}

	private static final RowMapper<EntityTypeDTO> ENTITY_TYPE_ROW_MAPPER = new RowMapper<>() {
		@Override
		public EntityTypeDTO mapRow(ResultSet rs, int rowNum) throws SQLException {
			return new EntityTypeDTO(rs.getString("entity_type"));
		}
	};

	@Override
	public Optional<EntityTypeDTO> getEntityTypeById(UUID entityTypeId) {
		return cluboneJdbcTemplate.query(ENTITY_TYPE_SQL, ENTITY_TYPE_ROW_MAPPER, entityTypeId).stream().findFirst();
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

		// 1) Insert transaction header (now also sets created_by)
		String sql = """
				    INSERT INTO transactions.transaction (
				        transaction_id,  client_payment_transaction_id,
				         invoice_id, transaction_date, created_on, created_by
				    ) VALUES (?,  ?,  ?, ?, NOW(), ?)
				""";

		cluboneJdbcTemplate.update(sql, transactionId, dto.getClientPaymentTransactionId(), dto.getInvoiceId(),
				dto.getTransactionDate(), dto.getCreatedBy() // <-- added
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
			return dto;
		}
	};

	@Override
	public Optional<InvoiceSummaryDTO> getInvoiceSummaryById(UUID invoiceId) {
		return cluboneJdbcTemplate.query(INVOICE_SUMMARY_SQL, INVOICE_SUMMARY_ROW_MAPPER, invoiceId).stream()
				.findFirst();
	}

	@Override
	public int updateClientAgreementId(UUID transactionId, UUID clientAgreementId) {
		return cluboneJdbcTemplate.update(UPDATE_TRANSACTION_CLIENT_AGREEMENT_SQL, clientAgreementId, transactionId);
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
		return cluboneJdbcTemplate.query(TAX_RATE_SQL, TAX_RATE_ROW_MAPPER, taxGroupId);
	}

	@Override
	public List<InvoiceFlatRow> findInvoicesWithLatestTxnByClientRole(UUID clientRoleId) {
		String sql = """
				SELECT
				    i.invoice_id,
				    i.invoice_number,
				    i.invoice_date::date AS invoice_date,
				    i.total_amount,
				    i.sub_total,
				    i.tax_amount,
				    i.discount_amount,
				    lt.transaction_code,
				    lt.client_agreement_id,
				    lt.client_payment_transaction_id,
				    lt.transaction_date
				FROM "transactions".invoice i
				LEFT JOIN (
				    SELECT DISTINCT ON (t.invoice_id)
				        t.invoice_id,
				        t.transaction_code,
				        t.client_agreement_id,
				        t.client_payment_transaction_id,
				        t.transaction_date
				    FROM "transactions"."transaction" t
				    ORDER BY t.invoice_id, t.transaction_date DESC NULLS LAST
				) lt ON lt.invoice_id = i.invoice_id
				WHERE i.client_role_id = ?
				""";

		return cluboneJdbcTemplate.query(sql, (rs, rowNum) -> mapInvoiceFlatRow(rs), clientRoleId);
	}

	@Override
	public List<InvoiceEntityRow> findEntitiesByInvoiceIds(List<UUID> invoiceIds) {
		if (invoiceIds.isEmpty()) {
			return Collections.emptyList();
		}

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
				ORDER BY ie.parent_invoice_entity_id NULLS FIRST, ie.invoice_entity_id
				""".formatted(inSql);

		return cluboneJdbcTemplate.query(sql, (rs, rowNum) -> mapInvoiceEntityRow(rs), invoiceIds.toArray());
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
			String name = cluboneJdbcTemplate.queryForObject(SQL_TYPE_NAME_BY_BUNDLE_ITEM_ID, String.class,
					bundleItemId);
			return Optional.ofNullable(name);
		} catch (EmptyResultDataAccessException e) {
			return Optional.empty();
		}
	}

	@Override
	public boolean isProrateApplicable(UUID planTemplateId) {
		Boolean ok = cluboneJdbcTemplate.queryForObject(SQL_IS_PRORATE_APPLICABLE, Boolean.class, planTemplateId);
		return ok != null && ok;
	}

	@Override
	public List<io.clubone.transaction.v2.vo.InvoiceSummaryDTO> findByClientRole(UUID clientRoleId, Integer limit,
			Integer offset) {
		return cluboneJdbcTemplate.query(SQL_INVOICE_SUMMARY, ps -> {
			ps.setObject(1, clientRoleId);
			ps.setObject(2, limit);
			ps.setObject(3, offset);
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
					rs.getString("frequency_name"), (Integer) rs.getObject("remaining_cycles"),

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
		// TODO: adjust schema/table names for the invoice table if different
		final String sql = """
																with inv as (
				  select *
				  from "transactions".invoice
				  where invoice_id = ?
				),
				sbh_pick as (
				  select sbh.*
				  from client_subscription_billing.subscription_billing_history sbh
				  join inv i on sbh.invoice_id = i.invoice_id
				  order by
				    sbh.billing_attempt_on desc nulls last,
				    sbh.payment_due_date   desc nulls last,
				    sbh.cycle_number       desc nulls last
				  limit 1
				),
				-- pick ONE invoice_entity row for this invoice (prefer items tied to a plan template)
				ie_pick as (
				  select ie.*
				  from "transactions".invoice_entity ie
				  join inv i on ie.invoice_id = i.invoice_id
				  order by
				    (ie.price_plan_template_id is not null) desc,
				    ie.total_amount desc nulls last,
				    ie.created_on  desc nulls last
				  limit 1
				)
				select
				  -- invoice
				  i.invoice_id,
				  i.invoice_number,
				  i.invoice_date::date                        as invoice_date,
				  coalesce(i.total_amount, 0)                 as amount,
				  case when i.is_paid then 0 else coalesce(i.total_amount,0) end as balance_due,
				  0::numeric                                  as write_off,
				  coalesce(lis.status_name, 'UNKNOWN')        as status,
				  null::text                                  as sales_rep,
				  i.level_id                                  as level_id,

				  -- billing history
				  sbh.subscription_instance_id,
				  sbh.amount_gross_incl_tax                   as amount_gross_incl_tax,

				  -- instance
				  si.subscription_plan_id,
				  si.start_date,
				  si.next_billing_date,
				  si.last_billed_on,
				  si.end_date,
				  si.current_cycle_number,

				  -- plan
				  sp.interval_count,
				  sp.subscription_frequency_id,
				  sp.contract_start_date,
				  sp.contract_end_date,
				  sp.entity_id,
				  sp.entity_type_id,

				  -- frequency
				  lf.frequency_name,

				  -- terms
				  spt.remaining_cycles,

				  -- NEW: parent entity info from the picked invoice_entity
				  ie.entity_id                                 as child_entity_id,
				  ie.entity_type_id                            as child_entity_type_id,
				  iep.entity_id                                as parent_entity_id,
				  iep.entity_type_id                           as parent_entity_type_id,

				  -- NEW: template fields
				  bpt.total_cycles                             as template_total_cycles,
				  bpt.level_id                                 as template_level_id,

				  -- NEW: final total_cycles preference: template -> computed from instance/term
				  coalesce(
				    bpt.total_cycles,
				    case
				      when si.current_cycle_number is not null and spt.remaining_cycles is not null
				        then si.current_cycle_number + spt.remaining_cycles
				      else null
				    end
				  )                                            as total_cycles

				from inv i
				join sbh_pick sbh on true
				join client_subscription_billing.subscription_instance si
				  on si.subscription_instance_id = sbh.subscription_instance_id
				join client_subscription_billing.subscription_plan sp
				  on sp.subscription_plan_id = si.subscription_plan_id
				join client_subscription_billing.lu_subscription_frequency lf
				  on lf.subscription_frequency_id = sp.subscription_frequency_id

				-- latest plan term (if any)
				left join lateral (
				  select spt.*
				  from client_subscription_billing.subscription_plan_term spt
				  where spt.subscription_plan_id = sp.subscription_plan_id
				  order by spt.modified_on desc nulls last
				  limit 1
				) spt on true

				-- pick one invoice_entity and its parent
				left join ie_pick ie on true
				left join "transactions".invoice_entity iep
				  on iep.invoice_entity_id = ie.parent_invoice_entity_id

				-- link to bundle plan template for cycles/level
				left join bundles_new.bundle_plan_template bpt
				  on bpt.plan_template_id = ie.price_plan_template_id

				left join "transactions".lu_invoice_status lis
				  on lis.invoice_status_id = i.invoice_status_id;


																""";

		try {
			return Optional.ofNullable(cluboneJdbcTemplate.queryForObject(sql, RAW_MAPPER, invoiceId));
		} catch (EmptyResultDataAccessException ex) {
			return Optional.empty();
		}
	}

	@Override
	public BigDecimal findEffectivePriceForCycle(UUID subscriptionPlanId, int cycleNumber) {
		final String sql = """
				select spcp.effective_unit_price
				from client_subscription_billing.subscription_plan_cycle_price spcp
				where spcp.subscription_plan_id = ?
				  and spcp.cycle_start <= ?
				  and (spcp.cycle_end is null or spcp.cycle_end >= ?)
				order by spcp.cycle_start desc
				limit 1
				""";
		return cluboneJdbcTemplate.query(sql, rs -> rs.next() ? rs.getBigDecimal(1) : null, subscriptionPlanId,
				cycleNumber, cycleNumber);
	}

	@Override
	public void activateAgreementAndClientStatusForInvoice(UUID invoiceId, UUID actorId) {
		if (invoiceId == null) {
			throw new IllegalArgumentException("invoiceId must not be null");
		}
		if (actorId == null) {
			throw new IllegalArgumentException("actorId must not be null");
		}

		// 1) Update client_agreements.client_agreement status to ACTIVE
		final String updateClientAgreementSql = """
				UPDATE client_agreements.client_agreement ca
				SET
				    client_agreement_status_id = (
				        SELECT las.client_agreement_status_id
				        FROM client_agreements.lu_client_agreement_status las
				        WHERE las.code = 'ACTIVE'
				          AND las.is_active = TRUE
				        LIMIT 1
				    ),
				    is_active = TRUE,
				    modified_on = NOW(),
				    modified_by = ?
				FROM transactions.invoice i
				WHERE i.invoice_id = ?
				  AND i.client_agreement_id IS NOT NULL
				  AND i.client_agreement_id = ca.client_agreement_id
				""";

		// order of args must match the ? placeholders: modified_by, invoice_id
		cluboneJdbcTemplate.update(updateClientAgreementSql, actorId, invoiceId);

		// 2) Update clients.client_role_status to ACTIVE values, again using joins &
		// subqueries
		final String updateClientRoleStatusSql = """
				UPDATE clients.client_role_status crs
				SET
				    agreement_status_id = (
				        SELECT las.client_agreement_status_id
				        FROM client_agreements.lu_client_agreement_status las
				        WHERE las.code = 'ACTIVE'
				          AND las.is_active = TRUE
				        LIMIT 1
				    ),
				    status_id = (
				        SELECT lcs.lu_client_status_id
				        FROM clients.lu_client_status lcs
				        WHERE lcs.client_status_type = 'Active'
				          AND COALESCE(lcs.is_active, TRUE) = TRUE
				        LIMIT 1
				    ),
				    account_status_id = (
				        SELECT lcas.lu_client_account_status_id
				        FROM clients.lu_client_account_status lcas
				        WHERE lcas.client_account_status_type = 'Active'
				          AND COALESCE(lcas.is_active, TRUE) = TRUE
				        LIMIT 1
				    ),
				    is_active = TRUE,
				    modified_on = NOW(),
				    modified_by = ?
				FROM transactions.invoice i
				JOIN client_agreements.client_agreement ca
				  ON ca.client_agreement_id = i.client_agreement_id
				WHERE i.invoice_id = ?
				  AND i.client_agreement_id IS NOT NULL
				  AND crs.client_role_id = ca.client_role_id
				  AND COALESCE(crs.is_active, TRUE) = TRUE
				""";

		cluboneJdbcTemplate.update(updateClientRoleStatusSql, actorId, invoiceId);
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
	    """;
	    return cluboneJdbcTemplate.queryForObject(sql, (rs, rn) -> new InvoiceSeedRow(
	        rs.getObject("invoice_id", UUID.class),
	        rs.getObject("client_role_id", UUID.class),
	        rs.getObject("level_id", UUID.class),
	        rs.getString("billing_address"),
	        rs.getObject("client_agreement_id", UUID.class),
	        rs.getObject("created_by", UUID.class)
	    ), invoiceId);
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
        ie.contract_start_date,

        ROW_NUMBER() OVER (
            PARTITION BY
                ie.parent_invoice_entity_id,
                ie.entity_type_id,
                ie.entity_id,
                ie.price_plan_template_id
            ORDER BY
                ie.contract_start_date DESC NULLS LAST,
                ie.created_on DESC,
                ie.invoice_entity_id DESC
        ) AS rn
    FROM transactions.invoice_entity ie
    JOIN transactions.invoice_entity_price_band iepb
        ON iepb.invoice_entity_id = ie.invoice_entity_id
    JOIN package.package_price_cycle_band bpcb
        ON bpcb.package_plan_template_id = ie.price_plan_template_id
       AND bpcb.cycle_range @> ?::int
    WHERE ie.invoice_id = ?
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
            from package.package_price_cycle_band b
            where b.package_plan_template_id = ?
              and b.start_cycle <= ?
              and (b.end_cycle is null or b.end_cycle >= ?)
            order by b.start_cycle desc
            limit 1
        """;

        List<CycleBandRef> rows = cluboneJdbcTemplate.query(
            sql,
            new Object[]{packagePlanTemplateId, cycleNumber, cycleNumber},
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
		// TODO Auto-generated method stub
		return null;
	}


	@Override
	public int resolveDefaultQtyFromEntitlement(UUID planTemplateId, UUID applicationId) {
	    if (planTemplateId == null) return 1;

	    final String sql = """
	        select coalesce(e.quantity_per_cycle, 1) as qty
	        from package.package_plan_template_entitlement e
	        where e.package_plan_template_id = ?
	        order by e.created_on desc
	        limit 1
	    """;

	    Integer q = cluboneJdbcTemplate.query(
	            sql,
	            rs -> rs.next() ? rs.getInt("qty") : 1,
	            planTemplateId
	    );

	    return (q == null || q <= 0) ? 1 : q;
	}
	@Override
	public UUID findBillingDayRuleIdForPlanTemplate(UUID planTemplateId) {
	    if (planTemplateId == null) return null;

	    final String sql = """
	        select bpt.subscription_billing_day_rule_id
	        from package.package_plan_template bpt
	        where bpt.package_plan_template_id = ?
	          and bpt.is_active = true
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
	        "select billing_day from client_subscription_billing.lu_subscription_billing_day_rule where subscription_billing_day_rule_id = ?",
	        "select billing_day_of_month from client_subscription_billing.lu_subscription_billing_day_rule where subscription_billing_day_rule_id = ?"
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
	          AND iep.is_active = true
	        LIMIT 1
	    """;
	    List<UUID> rows = cluboneJdbcTemplate.query(sql, (rs, i) -> rs.getObject(1, UUID.class), invoiceId);
	    return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
	}

	@Override
	public String findFrequencyNameForPlanTemplate(UUID planTemplateId) {
	    if (planTemplateId == null) return null;

	    return cluboneJdbcTemplate.query(
	        """
	        SELECT f.frequency_name
	        FROM package.package_plan_template bpt
	        JOIN client_subscription_billing.lu_subscription_frequency f
	          ON f.subscription_frequency_id = bpt.subscription_frequency_id
	        WHERE bpt.package_plan_template_id = ?
	          AND bpt.is_active = true
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
	        SELECT interval_count
	        FROM package.package_plan_template
	        WHERE package_plan_template_id = ?
	          AND is_active = true
	        """,
	        rs -> rs.next() ? (Integer) rs.getInt(1) : null,
	        planTemplateId
	    );
	}

	@Override
	public String findBillingDayText(UUID billingDayRuleId) {
	    if (billingDayRuleId == null) return null;

	    return cluboneJdbcTemplate.query(
	        """
	        SELECT billing_day
	        FROM client_subscription_billing.lu_subscription_billing_day_rule
	        WHERE subscription_billing_day_rule_id = ?
	          AND is_active = true
	        """,
	        rs -> rs.next() ? rs.getString(1) : null,
	        billingDayRuleId
	    );
	}


}
