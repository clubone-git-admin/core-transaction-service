package io.clubone.transaction.pos.catalog;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class PosAgreementCatalogDaoImpl implements PosAgreementCatalogDao {

	@Autowired
	@Qualifier("cluboneJdbcTemplate")
	private JdbcTemplate cluboneJdbcTemplate;

	private static final RowMapper<AgreementCatalogRow> AGREEMENT_ROW = (rs, rn) -> new AgreementCatalogRow(
			rs.getObject("agreement_id", UUID.class),
			rs.getString("agreement_name"),
			rs.getObject("agreement_version_id", UUID.class),
			rs.getString("config"));

	@Override
	public List<AgreementCatalogRow> findAgreementsForLevel(UUID levelId, LocalDate asOf) {
		final String sql = """
				WITH av_pick AS (
				  SELECT DISTINCT ON (a.agreement_id)
				    a.agreement_id,
				    a.agreement_name,
				    av.agreement_version_id,
				    av.config::text AS config
				  FROM agreements.agreement a
				  JOIN agreements.agreement_version av ON av.agreement_id = a.agreement_id
				  WHERE COALESCE(a.is_active, true) = true
				    AND COALESCE(av.is_active, true) = true
				    AND COALESCE(av.is_published, true) = true
				    AND CAST(av.valid_from AS date) <= CAST(? AS date)
				    AND (av.valid_to IS NULL OR CAST(av.valid_to AS date) >= CAST(? AS date))
				  ORDER BY a.agreement_id, (av.is_current IS TRUE) DESC, av.valid_from DESC
				)
				SELECT ap.agreement_id, ap.agreement_name, ap.agreement_version_id, ap.config
				FROM av_pick ap
				WHERE EXISTS (
				  SELECT 1
				  FROM agreements.agreement_location al
				  WHERE al.agreement_id = ap.agreement_id
				    AND al.agreement_version_id = ap.agreement_version_id
				    AND al.level_id = ?
				    AND COALESCE(al.is_active, true) = true
				    AND CAST(al.start_date AS date) <= CAST(? AS date)
				    AND (al.end_date IS NULL OR CAST(al.end_date AS date) >= CAST(? AS date))
				)
				ORDER BY ap.agreement_name
				""";
		return cluboneJdbcTemplate.query(sql, AGREEMENT_ROW, asOf, asOf, levelId, asOf, asOf);
	}

	@Override
	public List<UUID> findBundleIdsForAgreementVersion(UUID agreementVersionId) {
		if (agreementVersionId == null) {
			return Collections.emptyList();
		}
		return cluboneJdbcTemplate.query("""
				SELECT bundle_id
				FROM agreements.agreement_version_bundle
				WHERE agreement_version_id = ?
				ORDER BY sort_order, bundle_id
				""", (rs, rn) -> rs.getObject("bundle_id", UUID.class), agreementVersionId);
	}

	@Override
	public BundleCatalogRow findBundleWithVersionAtLevel(UUID bundleId, UUID levelId, LocalDate asOf) {
		final String sql = """
				SELECT
				  b.bundle_id,
				  COALESCE(b.description, '') AS bundle_name,
				  bv.bundle_version_id,
				  bv.config::text AS config
				FROM bundles_new.bundle b
				JOIN bundles_new.bundle_location bl
				  ON bl.bundle_id = b.bundle_id AND bl.level_id = ?
				JOIN LATERAL (
				  SELECT v.bundle_version_id, v.config
				  FROM bundles_new.bundle_version v
				  WHERE v.bundle_id = b.bundle_id
				    AND COALESCE(v.is_active, true) = true
				    AND COALESCE(v.is_published, true) = true
				    AND (v.valid_from IS NULL OR v.valid_from <= CAST(? AS date))
				    AND (v.valid_to IS NULL OR v.valid_to >= CAST(? AS date))
				  ORDER BY (v.is_current IS TRUE) DESC, v.valid_from DESC NULLS LAST
				  LIMIT 1
				) bv ON TRUE
				WHERE b.bundle_id = ?
				  AND COALESCE(b.is_active, true) = true
				""";
		try {
			return cluboneJdbcTemplate.queryForObject(sql,
					(rs, rn) -> new BundleCatalogRow(
							rs.getObject("bundle_id", UUID.class),
							rs.getString("bundle_name"),
							rs.getObject("bundle_version_id", UUID.class),
							rs.getString("config")),
					levelId, asOf, asOf, bundleId);
		} catch (EmptyResultDataAccessException e) {
			return null;
		}
	}

	@Override
	public List<BundleItemRow> findBundleItems(UUID bundleId) {
		return cluboneJdbcTemplate.query("""
				SELECT bi.item_id, bi.item_quantity
				FROM bundles_new.bundle_item bi
				WHERE bi.bundle_id = ?
				ORDER BY bi.bundle_item_id
				""", (rs, rn) -> new BundleItemRow(rs.getObject("item_id", UUID.class),
				rs.getObject("item_quantity") == null ? BigDecimal.ONE : rs.getBigDecimal("item_quantity")),
				bundleId);
	}

	@Override
	public ItemVersionRow findItemVersion(UUID itemId, LocalDate asOf) {
		final String sql = """
				SELECT DISTINCT ON (iv.item_id)
				  iv.item_id,
				  iv.item_version_id,
				  COALESCE(i.item_name, '') AS item_name,
				  iv.config::text AS config
				FROM items.item i
				JOIN items.item_version iv ON iv.item_id = i.item_id
				WHERE i.item_id = ?
				  AND COALESCE(i.is_active, true) = true
				  AND COALESCE(iv.is_active, true) = true
				  AND COALESCE(iv.is_published, true) = true
				  AND (iv.valid_from IS NULL OR CAST(iv.valid_from AS date) <= CAST(? AS date))
				  AND (iv.valid_to IS NULL OR CAST(iv.valid_to AS date) >= CAST(? AS date))
				ORDER BY iv.item_id, (iv.is_current IS TRUE) DESC, iv.valid_from DESC NULLS LAST
				""";
		try {
			return cluboneJdbcTemplate.queryForObject(sql, (rs, rn) -> new ItemVersionRow(
					rs.getObject("item_id", UUID.class),
					rs.getObject("item_version_id", UUID.class),
					rs.getString("item_name"),
					rs.getString("config")), itemId, asOf, asOf);
		} catch (EmptyResultDataAccessException e) {
			return null;
		}
	}
}
