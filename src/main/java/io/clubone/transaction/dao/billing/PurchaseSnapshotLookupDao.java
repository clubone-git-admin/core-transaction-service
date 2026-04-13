package io.clubone.transaction.dao.billing;

import java.util.Optional;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Resolves {@code client_subscription_billing.lu_purchase_snapshot_*} rows by stable {@code code}.
 * <p>
 * Seed data must match {@code docs/subscription-billing-snapshot-complete.sql}.
 */
@Repository
public class PurchaseSnapshotLookupDao {

	private final JdbcTemplate jdbc;

	public PurchaseSnapshotLookupDao(@Qualifier("cluboneJdbcTemplate") JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	public UUID requirePurchaseSnapshotKindId(String code) {
		return queryKindId(code, "lu_purchase_snapshot_kind", "purchase_snapshot_kind_id");
	}

	public UUID requirePurchaseSnapshotLineTypeId(String code) {
		return queryKindId(code, "lu_purchase_snapshot_line_type", "purchase_snapshot_line_type_id");
	}

	public UUID requirePurchaseSnapshotLegalTypeId(String code) {
		return queryKindId(code, "lu_purchase_snapshot_legal_type", "purchase_snapshot_legal_type_id");
	}

	/**
	 * Resolves {@code lu_purchase_snapshot_promo_discount_type} by {@code code}; empty when missing or inactive.
	 */
	public Optional<UUID> findPurchaseSnapshotPromoDiscountTypeId(String code) {
		if (code == null || code.isBlank()) {
			return Optional.empty();
		}
		String sql = """
				SELECT purchase_snapshot_promo_discount_type_id
				FROM client_subscription_billing.lu_purchase_snapshot_promo_discount_type
				WHERE UPPER(TRIM(code)) = UPPER(TRIM(?))
				  AND COALESCE(is_active, true) = true
				LIMIT 1
				""";
		try {
			return Optional.of(jdbc.queryForObject(sql, UUID.class, code.trim()));
		} catch (EmptyResultDataAccessException e) {
			return Optional.empty();
		}
	}

	private UUID queryKindId(String code, String table, String idColumn) {
		if (code == null || code.isBlank()) {
			throw new IllegalArgumentException("Lookup code is required");
		}
		String sql = """
				SELECT %s
				FROM client_subscription_billing.%s
				WHERE UPPER(TRIM(code)) = UPPER(TRIM(?))
				  AND COALESCE(is_active, true) = true
				LIMIT 1
				""".formatted(idColumn, table);
		try {
			return jdbc.queryForObject(sql, UUID.class, code.trim());
		} catch (EmptyResultDataAccessException e) {
			throw new IllegalStateException(
					"Missing lookup client_subscription_billing." + table + " code='" + code.trim()
							+ "'. Apply docs/subscription-billing-snapshot-complete.sql (seed section).",
					e);
		}
	}
}
