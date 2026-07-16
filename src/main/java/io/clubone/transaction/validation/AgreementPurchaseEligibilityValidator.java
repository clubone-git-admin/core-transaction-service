package io.clubone.transaction.validation;

import java.sql.Types;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;
import org.springframework.web.server.ResponseStatusException;

import io.clubone.transaction.dao.TransactionDAO;
import io.clubone.transaction.v2.vo.Entity;
import io.clubone.transaction.v2.vo.InvoiceRequest;
import io.clubone.transaction.vo.EntityTypeDTO;

@Component
public class AgreementPurchaseEligibilityValidator {

    private static final Logger log =
            LoggerFactory.getLogger(AgreementPurchaseEligibilityValidator.class);

    private static final String CLASSIFICATION_INDIVIDUAL =
            "INDIVIDUAL";

    private static final String CLASSIFICATION_CORP_INDIVIDUAL =
            "CORP-INDIVIDUAL";

    private static final String CLASSIFICATION_CORPORATE =
            "CORPORATE";

    private static final String ROLE_TYPE_ORGANIZATION_ACCOUNT =
            "ORGANIZATION ACCOUNT";

    private final NamedParameterJdbcTemplate jdbc;
    private final TransactionDAO transactionDAO;

    public AgreementPurchaseEligibilityValidator(
            @Qualifier("cluboneNamedJdbcTemplate")
            NamedParameterJdbcTemplate jdbc,
            TransactionDAO transactionDAO
    ) {
        this.jdbc = jdbc;
        this.transactionDAO = transactionDAO;
    }

    /**
     * Validates every agreement in the invoice request before any invoice,
     * client agreement, organization contract, or invoice line is created.
     */
    public void validate(InvoiceRequest request) {
        if (request == null) {
            return;
        }

        UUID applicationId = request.getApplicationId();
        UUID clientRoleId = request.getClientRoleId();

        if (applicationId == null || clientRoleId == null) {
            return;
        }

        List<AgreementRequestRef> agreementRefs =
                findAgreementEntities(request);

        if (agreementRefs.isEmpty()) {
            return;
        }

        PurchaserProfile purchaser =
                loadPurchaserProfile(
                        applicationId,
                        clientRoleId,
                        LocalDate.now()
                );

        List<AgreementProfile> agreements =
                loadAgreementProfiles(
                        applicationId,
                        agreementRefs
                );

        validateAllAgreementIdsResolved(
                agreementRefs,
                agreements
        );

        for (AgreementProfile agreement : agreements) {
            validateAgreementPurchase(
                    purchaser,
                    agreement
            );
        }

        log.info(
                "Agreement purchase eligibility validation passed. " +
                "applicationId={}, clientRoleId={}, purchaserType={}, " +
                "agreementCount={}",
                applicationId,
                clientRoleId,
                purchaser.purchaserType(),
                agreements.size()
        );
    }

    private List<AgreementRequestRef> findAgreementEntities(
            InvoiceRequest request
    ) {
        if (CollectionUtils.isEmpty(request.getEntities())) {
            return List.of();
        }

        List<AgreementRequestRef> agreements = new ArrayList<>();

        for (int index = 0; index < request.getEntities().size(); index++) {
            final int entityIndex = index;
            final Entity entity = request.getEntities().get(entityIndex);

            if (entity == null) {
                continue;
            }

            final UUID entityTypeId = entity.getEntityTypeId();

            if (entityTypeId == null) {
                throw new ResponseStatusException(
                        HttpStatus.BAD_REQUEST,
                        "entities[" + entityIndex + "].entityTypeId is required"
                );
            }

            final EntityTypeDTO entityType =
                    transactionDAO.getEntityTypeById(entityTypeId)
                            .orElseThrow(() ->
                                    new ResponseStatusException(
                                            HttpStatus.BAD_REQUEST,
                                            "Unknown entities[" +
                                                    entityIndex +
                                                    "].entityTypeId: " +
                                                    entityTypeId
                                    )
                            );

            if (!"AGREEMENT".equalsIgnoreCase(
                    safe(entityType.getEntityType())
            )) {
                continue;
            }

            if (entity.getEntityId() == null) {
                throw new ResponseStatusException(
                        HttpStatus.BAD_REQUEST,
                        "entities[" +
                                entityIndex +
                                "].entityId is required for an Agreement"
                );
            }

            agreements.add(
                    new AgreementRequestRef(
                            entityIndex,
                            entity.getEntityId(),
                            entity.getEntityVersionId(),
                            entity.getEntityName()
                    )
            );
        }

        return agreements;
    }

    private PurchaserProfile loadPurchaserProfile(
            UUID applicationId,
            UUID clientRoleId,
            LocalDate eligibilityDate
    ) {
        MapSqlParameterSource params =
                new MapSqlParameterSource()
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
                                "eligibilityDate",
                                eligibilityDate,
                                Types.DATE
                        );

        List<PurchaserProfile> rows = jdbc.query("""
            SELECT
                cr.client_role_id,
                cr.role_id,
                crt.name AS client_role_type_name,

                EXISTS (
                    SELECT 1
                    FROM organization.organization o
                    WHERE o.application_id = :applicationId
                      AND o.client_role_id = cr.client_role_id
                      AND o.is_active = TRUE
                      AND o.effective_start_date <= :eligibilityDate
                      AND (
                            o.effective_end_date IS NULL
                            OR o.effective_end_date >= :eligibilityDate
                          )
                ) AS linked_organization_client,

                EXISTS (
                    SELECT 1
                    FROM organization.organization_member om
                    JOIN organization.organization o
                      ON o.organization_id = om.organization_id
                     AND o.application_id = om.application_id
                     AND o.is_active = TRUE
                     AND o.effective_start_date <= :eligibilityDate
                     AND (
                           o.effective_end_date IS NULL
                           OR o.effective_end_date >= :eligibilityDate
                         )
                    WHERE om.application_id = :applicationId
                      AND om.client_role_id = cr.client_role_id
                      AND om.is_active = TRUE
                      AND UPPER(TRIM(om.member_status_code)) = 'ACTIVE'
                      AND om.effective_start_date <= :eligibilityDate
                      AND (
                            om.effective_end_date IS NULL
                            OR om.effective_end_date >= :eligibilityDate
                          )
                ) AS active_organization_member,

                (
                    SELECT om.organization_id
                    FROM organization.organization_member om
                    JOIN organization.organization o
                      ON o.organization_id = om.organization_id
                     AND o.application_id = om.application_id
                     AND o.is_active = TRUE
                    WHERE om.application_id = :applicationId
                      AND om.client_role_id = cr.client_role_id
                      AND om.is_active = TRUE
                      AND UPPER(TRIM(om.member_status_code)) = 'ACTIVE'
                      AND om.effective_start_date <= :eligibilityDate
                      AND (
                            om.effective_end_date IS NULL
                            OR om.effective_end_date >= :eligibilityDate
                          )
                    ORDER BY om.created_on
                    LIMIT 1
                ) AS member_organization_id,

                (
                    SELECT o.organization_id
                    FROM organization.organization o
                    WHERE o.application_id = :applicationId
                      AND o.client_role_id = cr.client_role_id
                      AND o.is_active = TRUE
                      AND o.effective_start_date <= :eligibilityDate
                      AND (
                            o.effective_end_date IS NULL
                            OR o.effective_end_date >= :eligibilityDate
                          )
                    ORDER BY o.created_on
                    LIMIT 1
                ) AS purchasing_organization_id

            FROM clients.client_role cr

            JOIN clients.client_role_type crt
              ON crt.client_role_type_id =
                 cr.client_role_type_id
             AND crt.is_active = TRUE

            WHERE cr.client_role_id = :clientRoleId
              AND cr.is_active = TRUE
            LIMIT 1
            """,
            params,
            (rs, rowNum) -> {
                String roleTypeName =
                        normalize(
                                rs.getString(
                                        "client_role_type_name"
                                )
                        );

                boolean roleTypeIsOrganizationAccount =
                        ROLE_TYPE_ORGANIZATION_ACCOUNT.equals(
                                roleTypeName
                        );

                boolean linkedOrganizationClient =
                        rs.getBoolean(
                                "linked_organization_client"
                        );

                boolean organizationMember =
                        rs.getBoolean(
                                "active_organization_member"
                        );

                boolean corporation =
                        roleTypeIsOrganizationAccount
                                || linkedOrganizationClient;

                PurchaserType purchaserType;

                if (corporation) {
                    purchaserType =
                            PurchaserType.CORPORATION;
                } else if (organizationMember) {
                    purchaserType =
                            PurchaserType.ORGANIZATION_MEMBER;
                } else {
                    purchaserType =
                            PurchaserType.NORMAL_CLIENT;
                }

                return new PurchaserProfile(
                        rs.getObject(
                                "client_role_id",
                                UUID.class
                        ),
                        rs.getString("role_id"),
                        rs.getString(
                                "client_role_type_name"
                        ),
                        purchaserType,
                        organizationMember,
                        corporation,
                        rs.getObject(
                                "member_organization_id",
                                UUID.class
                        ),
                        rs.getObject(
                                "purchasing_organization_id",
                                UUID.class
                        )
                );
            }
        );

        if (rows.isEmpty()) {
            throw new ResponseStatusException(
            		 HttpStatus.BAD_REQUEST,
                    "The selected client is inactive or does not exist. " +
                    "Please select an active client before purchasing an agreement."
            );
        }

        return rows.get(0);
    }

    private List<AgreementProfile> loadAgreementProfiles(
            UUID applicationId,
            List<AgreementRequestRef> refs
    ) {
        Set<UUID> agreementIds =
                new LinkedHashSet<>();

        for (AgreementRequestRef ref : refs) {
            agreementIds.add(ref.agreementId());
        }

        MapSqlParameterSource params =
                new MapSqlParameterSource()
                        .addValue(
                                "applicationId",
                                applicationId,
                                Types.OTHER
                        )
                        .addValue(
                                "agreementIds",
                                new ArrayList<>(agreementIds)
                        );

        return jdbc.query("""
            SELECT
                a.agreement_id,
                a.agreement_name,
                a.current_version_id,
                a.is_active,
                ac.agreement_classification_id,
                ac.name AS classification_name
            FROM agreements.agreement a

            JOIN agreements.lu_agreement_classification ac
              ON ac.agreement_classification_id =
                 a.agreement_classification_id
             AND ac.is_active = TRUE
             AND (
                   ac.application_id = :applicationId
                   OR ac.application_id IS NULL
                 )

            WHERE a.application_id = :applicationId
              AND a.agreement_id IN (:agreementIds)
              AND a.is_active = TRUE
            """,
            params,
            (rs, rowNum) ->
                    new AgreementProfile(
                            rs.getObject(
                                    "agreement_id",
                                    UUID.class
                            ),
                            rs.getString(
                                    "agreement_name"
                            ),
                            normalize(
                                    rs.getString(
                                            "classification_name"
                                    )
                            ),
                            rs.getObject(
                                    "agreement_classification_id",
                                    UUID.class
                            ),
                            rs.getObject(
                                    "current_version_id",
                                    UUID.class
                            )
                    )
        );
    }

    private void validateAllAgreementIdsResolved(
            List<AgreementRequestRef> requested,
            List<AgreementProfile> loaded
    ) {
        Set<UUID> loadedIds =
                new LinkedHashSet<>();

        for (AgreementProfile agreement : loaded) {
            loadedIds.add(agreement.agreementId());
        }

        for (AgreementRequestRef ref : requested) {
            if (!loadedIds.contains(ref.agreementId())) {
                throw new ResponseStatusException(
                        HttpStatus.BAD_REQUEST,
                        "Agreement '" +
                                displayAgreementName(ref) +
                                "' is inactive, does not exist, or does not belong " +
                                "to the current application."
                );
            }
        }
    }

    private void validateAgreementPurchase(
            PurchaserProfile purchaser,
            AgreementProfile agreement
    ) {
        String classification =
                agreement.classificationName();

        if (classification == null
                || classification.isBlank()) {
            deny(
                    purchaser,
                    agreement,
                    "The agreement does not have a valid classification."
            );
        }

        switch (purchaser.purchaserType()) {
            case NORMAL_CLIENT -> {
                if (!CLASSIFICATION_INDIVIDUAL.equals(
                        classification
                )) {
                    deny(
                            purchaser,
                            agreement,
                            "A normal client can purchase only an " +
                            "INDIVIDUAL agreement."
                    );
                }
            }

            case ORGANIZATION_MEMBER -> {
                boolean allowed =
                        CLASSIFICATION_INDIVIDUAL.equals(
                                classification
                        )
                                || CLASSIFICATION_CORP_INDIVIDUAL.equals(
                                        classification
                                );

                if (!allowed) {
                    deny(
                            purchaser,
                            agreement,
                            "An organization member can purchase only " +
                            "INDIVIDUAL or CORP-INDIVIDUAL agreements. " +
                            "A CORPORATE agreement must be purchased by the " +
                            "organization account."
                    );
                }
            }

            case CORPORATION -> {
                if (!CLASSIFICATION_CORPORATE.equals(
                        classification
                )) {
                    deny(
                            purchaser,
                            agreement,
                            "An Organization Account can purchase only a " +
                            "CORPORATE agreement."
                    );
                }
            }
        }
    }

    private void deny(
            PurchaserProfile purchaser,
            AgreementProfile agreement,
            String reason
    ) {
        String purchaserLabel =
                switch (purchaser.purchaserType()) {
                    case NORMAL_CLIENT ->
                            "normal client";
                    case ORGANIZATION_MEMBER ->
                            "organization member";
                    case CORPORATION ->
                            "organization account";
                };

        String message =
                "Agreement purchase is not allowed. "
                        + "Client "
                        + safeDisplay(
                                purchaser.roleId(),
                                purchaser.clientRoleId().toString()
                          )
                        + " is identified as a "
                        + purchaserLabel
                        + ", but agreement '"
                        + safeDisplay(
                                agreement.agreementName(),
                                agreement.agreementId().toString()
                          )
                        + "' is classified as "
                        + agreement.classificationName()
                        + ". "
                        + reason;

        log.warn(
                "Agreement eligibility denied. clientRoleId={}, roleId={}, " +
                "purchaserType={}, agreementId={}, agreementName={}, " +
                "classification={}, reason={}",
                purchaser.clientRoleId(),
                purchaser.roleId(),
                purchaser.purchaserType(),
                agreement.agreementId(),
                agreement.agreementName(),
                agreement.classificationName(),
                reason
        );

        throw new ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                message
        );
    }

    private String displayAgreementName(
            AgreementRequestRef ref
    ) {
        return safeDisplay(
                ref.entityName(),
                ref.agreementId().toString()
        );
    }

    private static String safeDisplay(
            String preferred,
            String fallback
    ) {
        return preferred != null
                && !preferred.trim().isEmpty()
                ? preferred.trim()
                : fallback;
    }

    private static String normalize(String value) {
        return value == null
                ? ""
                : value.trim()
                        .toUpperCase(Locale.ROOT);
    }

    private static String safe(String value) {
        return value == null ? "" : value.trim();
    }

    private enum PurchaserType {
        NORMAL_CLIENT,
        ORGANIZATION_MEMBER,
        CORPORATION
    }

    private record AgreementRequestRef(
            int requestIndex,
            UUID agreementId,
            UUID agreementVersionId,
            String entityName
    ) {
    }

    private record AgreementProfile(
            UUID agreementId,
            String agreementName,
            String classificationName,
            UUID agreementClassificationId,
            UUID currentVersionId
    ) {
    }

    private record PurchaserProfile(
            UUID clientRoleId,
            String roleId,
            String clientRoleTypeName,
            PurchaserType purchaserType,
            boolean organizationMember,
            boolean corporation,
            UUID memberOrganizationId,
            UUID purchasingOrganizationId
    ) {
    }
}