package io.clubone.transaction.helper;

import java.sql.Types;
import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OrganizationAgreementLinkService {

    private static final Logger log =
            LoggerFactory.getLogger(OrganizationAgreementLinkService.class);

    private final NamedParameterJdbcTemplate named;

    public OrganizationAgreementLinkService(
            @Qualifier("cluboneNamedJdbcTemplate")
            NamedParameterJdbcTemplate named) {
        this.named = named;
    }

    /**
     * Creates or synchronizes an organization contract only when the purchaser
     * clientRoleId represents an organization.
     *
     * For normal member/client purchases this method performs no insert.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public OrganizationContractLinkResult linkPurchasedAgreement(
            UUID applicationId,
            UUID clientRoleId,
            UUID clientAgreementId,
            UUID actorId
    ) {
        if (applicationId == null) {
            throw new IllegalArgumentException("applicationId is required");
        }
        if (clientRoleId == null) {
            throw new IllegalArgumentException("clientRoleId is required");
        }
        if (clientAgreementId == null) {
            throw new IllegalArgumentException("clientAgreementId is required");
        }

        UUID organizationId = findOrganizationId(
                applicationId,
                clientRoleId
        );

        /*
         * A regular member purchased the agreement.
         * Do not create organization_contract.
         */
        if (organizationId == null) {
            log.debug(
                    "ClientRoleId {} is not an organization purchasing client; "
                            + "organization contract link skipped",
                    clientRoleId
            );

            return new OrganizationContractLinkResult(
                    null,
                    null,
                    clientAgreementId,
                    false,
                    false
            );
        }

        verifyClientAgreementOwnership(
                clientRoleId,
                clientAgreementId
        );

        UUID existingContractId = findContractId(
                applicationId,
                organizationId,
                clientAgreementId
        );

        if (existingContractId != null) {
            synchronizeExistingContract(
                    applicationId,
                    organizationId,
                    clientAgreementId,
                    actorId
            );

            log.info(
                    "Organization contract synchronized: organizationId={}, "
                            + "organizationContractId={}, clientAgreementId={}",
                    organizationId,
                    existingContractId,
                    clientAgreementId
            );

            return new OrganizationContractLinkResult(
                    organizationId,
                    existingContractId,
                    clientAgreementId,
                    false,
                    true
            );
        }

        UUID organizationContractId = UUID.randomUUID();

        int inserted = named.update("""
            INSERT INTO organization.organization_contract (
                organization_contract_id,
                organization_id,
                application_id,
                agreement_group_id,
                agreement_id,
                agreement_version_id,
                client_agreement_id,
                contract_number,
                contract_name,
                contract_role_code,
                contract_status_code,
                start_date,
                end_date,
                obligation_end_date,
                facility_level_id,
                is_signed,
                signed_on,
                commission_code,
                notes,
                is_active,
                created_on,
                created_by
            )
            SELECT
                :organizationContractId,
                o.organization_id,
                o.application_id,
                NULL,
                ca.agreement_id,
                ca.agreement_version_id,
                ca.client_agreement_id,
                ca.client_agreement_code,
                COALESCE(
                    NULLIF(TRIM(a.agreement_name), ''),
                    NULLIF(TRIM(o.name), ''),
                    ca.client_agreement_code
                ),
                'PRIMARY',
                CASE
                    WHEN UPPER(COALESCE(cas.code, '')) IN (
                        'ACTIVE',
                        'CURRENT'
                    ) THEN 'ACTIVE'

                    WHEN UPPER(COALESCE(cas.code, '')) IN (
                        'ON_HOLD',
                        'HOLD',
                        'FROZEN',
                        'FREEZE'
                    ) THEN 'ON_HOLD'

                    WHEN UPPER(COALESCE(cas.code, '')) IN (
                        'CANCELLED',
                        'CANCELED'
                    ) THEN 'CANCELLED'

                    WHEN UPPER(COALESCE(cas.code, '')) = 'EXPIRED'
                        THEN 'EXPIRED'

                    WHEN UPPER(COALESCE(cas.code, '')) = 'TERMINATED'
                        THEN 'TERMINATED'

                    ELSE 'PENDING'
                END,
                ca.start_date_utc::date,
                ca.end_date_utc::date,
                ca.obligation_end_utc::date,
                COALESCE(
                    ca.purchased_level_id,
                    o.home_level_id
                ),
                ca.is_signed,
                ca.signed_on_utc,
                NULL,
                'Created automatically during organization agreement purchase',
                TRUE,
                now(),
                :actorId
            FROM organization.organization o

            JOIN client_agreements.client_agreement ca
              ON ca.client_agreement_id = :clientAgreementId
             AND ca.client_role_id = o.client_role_id

            JOIN agreements.agreement a
              ON a.agreement_id = ca.agreement_id

            JOIN client_agreements.lu_client_agreement_status cas
              ON cas.client_agreement_status_id =
                 ca.client_agreement_status_id

            WHERE o.organization_id = :organizationId
              AND o.application_id = :applicationId
              AND o.client_role_id = :clientRoleId
              AND o.is_active = TRUE
            """,
            new MapSqlParameterSource()
                    .addValue(
                            "organizationContractId",
                            organizationContractId,
                            Types.OTHER
                    )
                    .addValue(
                            "organizationId",
                            organizationId,
                            Types.OTHER
                    )
                    .addValue(
                            "applicationId",
                            applicationId,
                            Types.OTHER
                    )
                    .addValue(
                            "clientRoleId",
                            clientRoleId,
                            Types.OTHER
                    )
                    .addValue(
                            "clientAgreementId",
                            clientAgreementId,
                            Types.OTHER
                    )
                    .addValue(
                            "actorId",
                            actorId,
                            Types.OTHER
                    )
        );

        if (inserted != 1) {
            throw new IllegalStateException(
                    "Unable to create organization contract for "
                            + "organizationId=" + organizationId
                            + ", clientAgreementId=" + clientAgreementId
            );
        }

        log.info(
                "Organization contract created: organizationId={}, "
                        + "organizationContractId={}, clientAgreementId={}, "
                        + "clientRoleId={}",
                organizationId,
                organizationContractId,
                clientAgreementId,
                clientRoleId
        );

        return new OrganizationContractLinkResult(
                organizationId,
                organizationContractId,
                clientAgreementId,
                true,
                true
        );
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void linkInvoice(
            UUID applicationId,
            UUID organizationId,
            UUID organizationContractId,
            UUID clientAgreementId,
            UUID invoiceId,
            UUID actorId
    ) {
        if (applicationId == null) {
            throw new IllegalArgumentException("applicationId is required");
        }
        if (organizationId == null) {
            throw new IllegalArgumentException("organizationId is required");
        }
        if (organizationContractId == null) {
            throw new IllegalArgumentException(
                    "organizationContractId is required"
            );
        }
        if (clientAgreementId == null) {
            throw new IllegalArgumentException(
                    "clientAgreementId is required"
            );
        }
        if (invoiceId == null) {
            throw new IllegalArgumentException("invoiceId is required");
        }

        MapSqlParameterSource params =
                new MapSqlParameterSource()
                        .addValue(
                                "applicationId",
                                applicationId,
                                Types.OTHER
                        )
                        .addValue(
                                "organizationId",
                                organizationId,
                                Types.OTHER
                        )
                        .addValue(
                                "organizationContractId",
                                organizationContractId,
                                Types.OTHER
                        )
                        .addValue(
                                "clientAgreementId",
                                clientAgreementId,
                                Types.OTHER
                        )
                        .addValue(
                                "invoiceId",
                                invoiceId,
                                Types.OTHER
                        )
                        .addValue(
                                "actorId",
                                actorId,
                                Types.OTHER
                        );

        int inserted = named.update("""
            INSERT INTO organization.organization_invoice_link (
                organization_invoice_link_id,
                organization_id,
                application_id,
                organization_account_id,
                organization_contract_id,
                client_agreement_id,
                invoice_id,
                invoice_number,
                invoice_date,
                due_date,
                description,
                subtotal_amount,
                discount_amount,
                tax_amount,
                total_amount,
                paid_amount,
                balance_amount,
                currency_code,
                invoice_status_code,
                metadata,
                is_active,
                created_on,
                created_by
            )
            SELECT
                gen_random_uuid(),
                :organizationId,
                :applicationId,
                NULL,
                :organizationContractId,
                :clientAgreementId,
                i.invoice_id,
                i.invoice_number,
                CAST(i.invoice_date AS date),
                NULL,
                'Created automatically during organization agreement purchase',
                COALESCE(i.sub_total, 0),
                COALESCE(i.discount_amount, 0),
                COALESCE(i.tax_amount, 0),
                COALESCE(i.total_amount, 0),

                CASE
                    WHEN i.is_paid = TRUE
                        THEN COALESCE(i.total_amount, 0)
                    ELSE 0
                END,

                CASE
                    WHEN i.is_paid = TRUE
                        THEN 0
                    ELSE COALESCE(i.total_amount, 0)
                END,

                'USD',

                CASE
                    WHEN i.is_paid = TRUE THEN 'PAID'

                    WHEN UPPER(
                        COALESCE(lis.status_name, '')
                    ) IN (
                        'DRAFT',
                        'OPEN',
                        'DUE',
                        'PAID',
                        'PARTIAL',
                        'VOID',
                        'CANCELLED',
                        'REFUNDED'
                    )
                    THEN UPPER(lis.status_name)

                    WHEN UPPER(
                        COALESCE(lis.status_name, '')
                    ) IN (
                        'PENDING',
                        'PENDING PAYMENT'
                    )
                    THEN 'OPEN'

                    WHEN UPPER(
                        COALESCE(lis.status_name, '')
                    ) IN (
                        'CANCELED',
                        'CANCEL'
                    )
                    THEN 'CANCELLED'

                    ELSE 'OPEN'
                END,

                jsonb_build_object(
                    'source',
                    'TRANSACTION_INVOICE',
                    'invoiceId',
                    i.invoice_id::text,
                    'clientAgreementId',
                    CAST(:clientAgreementId AS text),
                    'transactionInvoiceStatus',
                    lis.status_name
                ),

                TRUE,
                now(),
                :actorId

            FROM transactions.invoice i

            LEFT JOIN transactions.lu_invoice_status lis
              ON lis.invoice_status_id =
                 i.invoice_status_id

            WHERE i.invoice_id = :invoiceId

              AND NOT EXISTS (
                  SELECT 1
                  FROM organization.organization_invoice_link oil
                  WHERE oil.application_id =
                        :applicationId

                    AND oil.organization_id =
                        :organizationId

                    AND oil.invoice_id =
                        i.invoice_id

                    AND oil.client_agreement_id =
                        :clientAgreementId

                    AND oil.is_active = TRUE
              )
            """,
            params
        );

        if (inserted == 1) {
            log.info(
                    "Organization invoice linked successfully: "
                            + "organizationId={}, "
                            + "organizationContractId={}, "
                            + "clientAgreementId={}, "
                            + "invoiceId={}",
                    organizationId,
                    organizationContractId,
                    clientAgreementId,
                    invoiceId
            );

            return;
        }

        Integer existingLinkCount = named.queryForObject("""
            SELECT COUNT(*)
            FROM organization.organization_invoice_link oil
            WHERE oil.application_id =
                  :applicationId

              AND oil.organization_id =
                  :organizationId

              AND oil.invoice_id =
                  :invoiceId

              AND oil.client_agreement_id =
                  :clientAgreementId

              AND oil.is_active = TRUE
            """,
            params,
            Integer.class
        );

        if (existingLinkCount != null
                && existingLinkCount > 0) {

            log.info(
                    "Organization invoice link already exists: "
                            + "organizationId={}, "
                            + "clientAgreementId={}, "
                            + "invoiceId={}",
                    organizationId,
                    clientAgreementId,
                    invoiceId
            );

            return;
        }

        Integer invoiceCount = named.queryForObject("""
            SELECT COUNT(*)
            FROM transactions.invoice i
            WHERE i.invoice_id = :invoiceId
            """,
            params,
            Integer.class
        );

        if (invoiceCount == null || invoiceCount == 0) {
            throw new IllegalStateException(
                    "Saved invoice was not found. "
                            + "invoiceId=" + invoiceId
            );
        }

        throw new IllegalStateException(
                "Organization invoice link was not created. "
                        + "organizationId=" + organizationId
                        + ", organizationContractId="
                        + organizationContractId
                        + ", clientAgreementId="
                        + clientAgreementId
                        + ", invoiceId=" + invoiceId
        );
    }
    
    private UUID findOrganizationId(
            UUID applicationId,
            UUID clientRoleId
    ) {
        List<UUID> rows = named.query("""
            SELECT o.organization_id
            FROM organization.organization o
            WHERE o.application_id = :applicationId
              AND o.client_role_id = :clientRoleId
              AND o.is_active = TRUE
            LIMIT 1
            """,
            new MapSqlParameterSource()
                    .addValue("applicationId", applicationId)
                    .addValue("clientRoleId", clientRoleId),
            (rs, rowNum) ->
                    rs.getObject("organization_id", UUID.class)
        );

        return rows.isEmpty() ? null : rows.get(0);
    }

    private UUID findContractId(
            UUID applicationId,
            UUID organizationId,
            UUID clientAgreementId
    ) {
        List<UUID> rows = named.query("""
            SELECT oc.organization_contract_id
            FROM organization.organization_contract oc
            WHERE oc.application_id = :applicationId
              AND oc.organization_id = :organizationId
              AND oc.client_agreement_id = :clientAgreementId
              AND oc.is_active = TRUE
            LIMIT 1
            """,
            new MapSqlParameterSource()
                    .addValue("applicationId", applicationId)
                    .addValue("organizationId", organizationId)
                    .addValue(
                            "clientAgreementId",
                            clientAgreementId
                    ),
            (rs, rowNum) ->
                    rs.getObject(
                            "organization_contract_id",
                            UUID.class
                    )
        );

        return rows.isEmpty() ? null : rows.get(0);
    }

    private void verifyClientAgreementOwnership(
            UUID clientRoleId,
            UUID clientAgreementId
    ) {
        Integer count = named.queryForObject("""
            SELECT COUNT(*)
            FROM client_agreements.client_agreement ca
            WHERE ca.client_agreement_id = :clientAgreementId
              AND ca.client_role_id = :clientRoleId
              AND ca.is_active = TRUE
            """,
            new MapSqlParameterSource()
                    .addValue(
                            "clientAgreementId",
                            clientAgreementId
                    )
                    .addValue("clientRoleId", clientRoleId),
            Integer.class
        );

        if (count == null || count == 0) {
            throw new IllegalStateException(
                    "Client agreement does not belong to purchasing "
                            + "clientRoleId. clientAgreementId="
                            + clientAgreementId
                            + ", clientRoleId=" + clientRoleId
            );
        }
    }

    private void synchronizeExistingContract(
            UUID applicationId,
            UUID organizationId,
            UUID clientAgreementId,
            UUID actorId
    ) {
        named.update("""
            UPDATE organization.organization_contract oc
               SET agreement_id = ca.agreement_id,
                   agreement_version_id =
                       ca.agreement_version_id,
                   contract_number =
                       COALESCE(
                           oc.contract_number,
                           ca.client_agreement_code
                       ),
                   contract_name =
                       COALESCE(
                           NULLIF(TRIM(oc.contract_name), ''),
                           NULLIF(TRIM(a.agreement_name), ''),
                           ca.client_agreement_code
                       ),
                   contract_status_code =
                       CASE
                           WHEN UPPER(
                               COALESCE(cas.code, '')
                           ) IN (
                               'ACTIVE',
                               'CURRENT'
                           ) THEN 'ACTIVE'

                           WHEN UPPER(
                               COALESCE(cas.code, '')
                           ) IN (
                               'ON_HOLD',
                               'HOLD',
                               'FROZEN',
                               'FREEZE'
                           ) THEN 'ON_HOLD'

                           WHEN UPPER(
                               COALESCE(cas.code, '')
                           ) IN (
                               'CANCELLED',
                               'CANCELED'
                           ) THEN 'CANCELLED'

                           WHEN UPPER(
                               COALESCE(cas.code, '')
                           ) = 'EXPIRED'
                               THEN 'EXPIRED'

                           WHEN UPPER(
                               COALESCE(cas.code, '')
                           ) = 'TERMINATED'
                               THEN 'TERMINATED'

                           ELSE 'PENDING'
                       END,
                   start_date =
                       ca.start_date_utc::date,
                   end_date =
                       ca.end_date_utc::date,
                   obligation_end_date =
                       ca.obligation_end_utc::date,
                   facility_level_id =
                       COALESCE(
                           ca.purchased_level_id,
                           oc.facility_level_id
                       ),
                   is_signed = ca.is_signed,
                   signed_on = ca.signed_on_utc,
                   modified_on = now(),
                   modified_by = :actorId
              FROM client_agreements.client_agreement ca

              JOIN agreements.agreement a
                ON a.agreement_id = ca.agreement_id

              JOIN client_agreements.lu_client_agreement_status cas
                ON cas.client_agreement_status_id =
                   ca.client_agreement_status_id

             WHERE oc.application_id = :applicationId
               AND oc.organization_id = :organizationId
               AND oc.client_agreement_id =
                   :clientAgreementId
               AND oc.is_active = TRUE
               AND ca.client_agreement_id =
                   oc.client_agreement_id
            """,
            new MapSqlParameterSource()
                    .addValue("applicationId", applicationId)
                    .addValue("organizationId", organizationId)
                    .addValue(
                            "clientAgreementId",
                            clientAgreementId
                    )
                    .addValue("actorId", actorId)
        );
    }
}