package io.clubone.transaction.dao.impl;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import io.clubone.transaction.dao.EntityLookupDao;
import io.clubone.transaction.v2.vo.EntityLevelInfoDTO;

@Service
public class EntityLookupDaoImpl implements EntityLookupDao {

	@Autowired
	@Qualifier("cluboneJdbcTemplate")
	private JdbcTemplate cluboneJdbcTemplate;

	private static final RowMapper<EntityLevelInfoDTO> MAPPER = new RowMapper<>() {
		@Override
		public EntityLevelInfoDTO mapRow(ResultSet rs, int rowNum) throws SQLException {
			return new EntityLevelInfoDTO((UUID) rs.getObject("entity_type_id"), rs.getString("entity_type"),
					(UUID) rs.getObject("entity_id"), rs.getString("entity_name"), (UUID) rs.getObject("level_id"),
					rs.getString("level_name"));
		}
	};

	@Override
	public Optional<EntityLevelInfoDTO> resolveEntityAndLevel(UUID entityTypeId, UUID entityId, UUID levelId) {
		
		System.out.println(entityTypeId+"  "+entityId+"  "+levelId);
		final String sql = """
				with x as (
				    select cast(? as uuid) as entity_type_id,
				           cast(? as uuid) as entity_id,
				           cast(? as uuid) as level_id
				)
				select
				  let.entity_type_id,
				  let.entity_type,
				  x.entity_id,
				  /* pick name from the correct table based on entity_type */
				  coalesce(it.item_name, b.package_name, a."agreement_name") as entity_name,
				  x.level_id,
				  l.name as level_name
				from x
				join "transactions".lu_entity_type let
				  on let.entity_type_id = x.entity_type_id
				left join items.item it
				  on it.item_id = x.entity_id
				 and upper(let.entity_type) = 'ITEM'
				left join package.package b
				  on b.package_id = x.entity_id
				 and upper(let.entity_type) = 'BUNDLE'
				left join agreements.agreement a
				  on a.agreement_id = x.entity_id
				 and upper(let.entity_type) = 'AGREEMENT'
				left join "locations".levels l
				  on l.level_id = x.level_id
				""";
		try {
			return Optional.ofNullable(cluboneJdbcTemplate.queryForObject(sql, MAPPER, entityTypeId, entityId, levelId));
		} catch (EmptyResultDataAccessException ex) {
			return Optional.empty();
		}
	}

	@Override
	public Optional<EntityLevelInfoDTO> resolveFromInvoiceEntity(UUID invoiceEntityId) {
		final String sql = """
				select
				  let.entity_type_id,
				  let.entity_type,
				  ie.entity_id,
				  coalesce(it.item_name, b.name, a."name") as entity_name,
				  /* prefer plan template level; fall back to invoice.level_id */
				  coalesce(bpt.level_id, inv.level_id) as level_id,
				  l.name as level_name
				from "transaction".invoice_entity ie
				join "transaction".lu_entity_type let
				  on let.entity_type_id = ie.entity_type_id
				left join items.item it
				  on it.item_id = ie.entity_id
				 and upper(let.entity_type) = 'ITEM'
				left join bundles_new.bundle b
				  on b.bundle_id = ie.entity_id
				 and upper(let.entity_type) = 'BUNDLE'
				left join agreements.agreement a
				  on a.agreement_id = ie.entity_id
				 and upper(let.entity_type) = 'AGREEMENT'
				left join bundles_new.bundle_plan_template bpt
				  on bpt.plan_template_id = ie.price_plan_template_id
				left join "transaction".invoice inv
				  on inv.invoice_id = ie.invoice_id
				left join "location".levels l
				  on l.level_id = coalesce(bpt.level_id, inv.level_id)
				where ie.invoice_entity_id = ?
				""";
		try {
			return Optional.ofNullable(cluboneJdbcTemplate.queryForObject(sql, MAPPER, invoiceEntityId));
		} catch (EmptyResultDataAccessException ex) {
			return Optional.empty();
		}
	}
}
