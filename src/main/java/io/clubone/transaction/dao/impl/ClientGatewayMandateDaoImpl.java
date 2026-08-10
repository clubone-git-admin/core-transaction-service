package io.clubone.transaction.dao.impl;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import io.clubone.transaction.dao.ClientGatewayMandateDao;
import io.clubone.transaction.security.AccessContext;

@Repository
public class ClientGatewayMandateDaoImpl implements ClientGatewayMandateDao {

	@Autowired
	@Qualifier("cluboneJdbcTemplate")
	private JdbcTemplate cluboneJdbcTemplate;

	@Override
	public int ensureOneMandatePerSubscriptionPlan(UUID parentInvoiceId, List<UUID> subscriptionPlanIds,
			UUID clientPaymentMethodId, UUID modifiedBy) {
		if (clientPaymentMethodId == null || subscriptionPlanIds == null || subscriptionPlanIds.isEmpty()) {
			return 0;
		}
		UUID applicationId = AccessContext.applicationId();
		Set<UUID> planIds = new LinkedHashSet<>();
		for (UUID id : subscriptionPlanIds) {
			if (id != null) {
				planIds.add(id);
			}
		}
		if (planIds.isEmpty()) {
			return 0;
		}

		UUID seedMandateId = parentInvoiceId != null ? findSeedMandateId(parentInvoiceId, applicationId) : null;
		if (seedMandateId == null) {
			seedMandateId = findUnlinkedSeedByCpm(clientPaymentMethodId, applicationId);
		}
		if (seedMandateId == null) {
			return 0;
		}

		int touched = 0;
		boolean seedConsumed = false;
		for (UUID planId : planIds) {
			UUID existingForPlan = findActiveMandateIdForPlan(planId, applicationId);
			if (existingForPlan != null) {
				touched += cluboneJdbcTemplate.update("""
						UPDATE client_payments.client_gateway_mandate
						SET client_payment_method_id = ?,
						    parent_invoice_id = COALESCE(parent_invoice_id, ?),
						    modified_on = NOW(),
						    modified_by = ?
						WHERE client_gateway_mandate_id = ?
						  AND application_id = ?
						""", clientPaymentMethodId, parentInvoiceId, modifiedBy, existingForPlan, applicationId);
				continue;
			}

			if (!seedConsumed && isSeedUnlinkedOrAssignable(seedMandateId, applicationId)) {
				touched += cluboneJdbcTemplate.update("""
						UPDATE client_payments.client_gateway_mandate
						SET subscription_plan_id = ?,
						    client_payment_method_id = ?,
						    parent_invoice_id = COALESCE(parent_invoice_id, ?),
						    modified_on = NOW(),
						    modified_by = ?
						WHERE client_gateway_mandate_id = ?
						  AND application_id = ?
						  AND (subscription_plan_id IS NULL OR subscription_plan_id = ?)
						""", planId, clientPaymentMethodId, parentInvoiceId, modifiedBy, seedMandateId, applicationId,
						planId);
				seedConsumed = true;
				continue;
			}

			touched += cloneMandateForPlan(seedMandateId, planId, clientPaymentMethodId, parentInvoiceId, modifiedBy,
					applicationId);
		}
		return touched;
	}

	@Override
	public int deactivateActiveMandatesForPlan(UUID subscriptionPlanId, UUID modifiedBy) {
		if (subscriptionPlanId == null) {
			return 0;
		}
		UUID applicationId = AccessContext.applicationId();
		UUID revokedStatusId = findMandateStatusId("CANCELLED");
		if (revokedStatusId == null) {
			revokedStatusId = findMandateStatusId("EXPIRED");
		}
		return cluboneJdbcTemplate.update("""
				UPDATE client_payments.client_gateway_mandate
				SET is_active = false,
				    mandate_status_id = COALESCE(?, mandate_status_id),
				    mandate_end_date = COALESCE(mandate_end_date, NOW()),
				    status_reason = COALESCE(status_reason, 'Deactivated for subscription plan lifecycle'),
				    modified_on = NOW(),
				    modified_by = ?
				WHERE subscription_plan_id = ?
				  AND application_id = ?
				  AND COALESCE(is_active, true)
				""", revokedStatusId, modifiedBy, subscriptionPlanId, applicationId);
	}

	@Override
	public int rebindMandateForPlan(UUID subscriptionPlanId, UUID newClientPaymentMethodId, UUID parentInvoiceId,
			UUID modifiedBy) {
		if (subscriptionPlanId == null || newClientPaymentMethodId == null) {
			return 0;
		}
		deactivateActiveMandatesForPlan(subscriptionPlanId, modifiedBy);
		return ensureOneMandatePerSubscriptionPlan(parentInvoiceId, List.of(subscriptionPlanId),
				newClientPaymentMethodId, modifiedBy);
	}

	private UUID findSeedMandateId(UUID parentInvoiceId, UUID applicationId) {
		try {
			return cluboneJdbcTemplate.queryForObject("""
					SELECT client_gateway_mandate_id
					FROM client_payments.client_gateway_mandate
					WHERE parent_invoice_id = ?
					  AND application_id = ?
					  AND COALESCE(is_active, true)
					ORDER BY
					  CASE WHEN subscription_plan_id IS NULL THEN 0 ELSE 1 END,
					  created_on ASC NULLS LAST
					LIMIT 1
					""", UUID.class, parentInvoiceId, applicationId);
		} catch (EmptyResultDataAccessException e) {
			return null;
		}
	}

	private UUID findUnlinkedSeedByCpm(UUID clientPaymentMethodId, UUID applicationId) {
		try {
			return cluboneJdbcTemplate.queryForObject("""
					SELECT client_gateway_mandate_id
					FROM client_payments.client_gateway_mandate
					WHERE client_payment_method_id = ?
					  AND application_id = ?
					  AND subscription_plan_id IS NULL
					  AND COALESCE(is_active, true)
					ORDER BY created_on DESC NULLS LAST
					LIMIT 1
					""", UUID.class, clientPaymentMethodId, applicationId);
		} catch (EmptyResultDataAccessException e) {
			return null;
		}
	}

	private UUID findActiveMandateIdForPlan(UUID subscriptionPlanId, UUID applicationId) {
		try {
			return cluboneJdbcTemplate.queryForObject("""
					SELECT client_gateway_mandate_id
					FROM client_payments.client_gateway_mandate
					WHERE subscription_plan_id = ?
					  AND application_id = ?
					  AND COALESCE(is_active, true)
					ORDER BY created_on DESC NULLS LAST
					LIMIT 1
					""", UUID.class, subscriptionPlanId, applicationId);
		} catch (EmptyResultDataAccessException e) {
			return null;
		}
	}

	private boolean isSeedUnlinkedOrAssignable(UUID seedMandateId, UUID applicationId) {
		Boolean unlinked = cluboneJdbcTemplate.queryForObject("""
				SELECT subscription_plan_id IS NULL
				FROM client_payments.client_gateway_mandate
				WHERE client_gateway_mandate_id = ?
				  AND application_id = ?
				""", Boolean.class, seedMandateId, applicationId);
		return Boolean.TRUE.equals(unlinked);
	}

	private UUID findMandateStatusId(String code) {
		if (code == null || code.isBlank()) {
			return null;
		}
		try {
			return cluboneJdbcTemplate.queryForObject("""
					SELECT gateway_mandate_status_id
					FROM client_payments.lu_gateway_mandate_status
					WHERE UPPER(code) = UPPER(?)
					  AND COALESCE(is_active, true)
					LIMIT 1
					""", UUID.class, code.trim());
		} catch (EmptyResultDataAccessException e) {
			return null;
		}
	}

	private int cloneMandateForPlan(UUID seedMandateId, UUID subscriptionPlanId, UUID clientPaymentMethodId,
			UUID parentInvoiceId, UUID modifiedBy, UUID applicationId) {
		List<UUID> ids = cluboneJdbcTemplate.query("""
				INSERT INTO client_payments.client_gateway_mandate (
				    client_role_id,
				    payment_gateway_id,
				    client_payment_method_id,
				    subscription_plan_id,
				    gateway_mandate_id,
				    gateway_customer_id,
				    gateway_payment_method_ref,
				    gateway_reference,
				    mandate_amount_minor,
				    mandate_currency,
				    mandate_start_date,
				    mandate_end_date,
				    mandate_max_amount_minor,
				    mandate_frequency,
				    mandate_expire_after,
				    mandate_status_id,
				    status_reason,
				    parent_invoice_id,
				    application_id,
				    is_active,
				    created_by,
				    modified_by
				)
				SELECT
				    seed.client_role_id,
				    seed.payment_gateway_id,
				    ?,
				    ?,
				    seed.gateway_mandate_id,
				    seed.gateway_customer_id,
				    seed.gateway_payment_method_ref,
				    seed.gateway_reference,
				    seed.mandate_amount_minor,
				    seed.mandate_currency,
				    seed.mandate_start_date,
				    seed.mandate_end_date,
				    seed.mandate_max_amount_minor,
				    seed.mandate_frequency,
				    seed.mandate_expire_after,
				    seed.mandate_status_id,
				    seed.status_reason,
				    COALESCE(?, seed.parent_invoice_id),
				    seed.application_id,
				    true,
				    ?,
				    ?
				FROM client_payments.client_gateway_mandate seed
				WHERE seed.client_gateway_mandate_id = ?
				  AND seed.application_id = ?
				RETURNING client_gateway_mandate_id
				""", (rs, rowNum) -> (UUID) rs.getObject(1), clientPaymentMethodId, subscriptionPlanId, parentInvoiceId,
				modifiedBy, modifiedBy, seedMandateId, applicationId);
		return ids == null || ids.isEmpty() ? 0 : 1;
	}
}
