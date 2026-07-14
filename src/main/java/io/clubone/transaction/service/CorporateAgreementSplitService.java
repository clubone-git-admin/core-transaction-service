package io.clubone.transaction.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class CorporateAgreementSplitService {

    private static final BigDecimal HUNDRED = new BigDecimal("100");

    private final NamedParameterJdbcTemplate jdbc;
    
    private static final Logger log =
            LoggerFactory.getLogger(CorporateAgreementSplitService.class);

    public CorporateAgreementSplitService(
            NamedParameterJdbcTemplate jdbc
    ) {
        this.jdbc = jdbc;
    }

    public record CorporateContext(
            UUID organizationId,
            UUID organizationAccountId,
            UUID organizationContractId,
            UUID organizationClientRoleId,
            int paymentTermsDays
    ) {
    }

    public record SplitRule(
            UUID agreementGroupPaymentAllocationId,
            UUID paymentRoleId,
            UUID paymentWhenId,
            UUID paymentAllocationTypeId,
            String paymentRoleCode,
            String paymentWhenCode,
            String allocationTypeCode,
            BigDecimal memberPercentage
    ) {
    }

    public record AmountSplit(
            BigDecimal fullSubtotal,
            BigDecimal fullDiscount,
            BigDecimal fullTax,
            BigDecimal fullTotal,

            BigDecimal memberSubtotal,
            BigDecimal memberDiscount,
            BigDecimal memberTax,
            BigDecimal memberTotal,

            BigDecimal corporateSubtotal,
            BigDecimal corporateDiscount,
            BigDecimal corporateTax,
            BigDecimal corporateTotal,

            BigDecimal memberPercentage,
            BigDecimal corporatePercentage
    ) {
    }

    /**
     * Returns null for every non-corporate agreement.
     *
     * This is the primary guard that prevents changes to Item, Package,
     * Bundle and normal Agreement flows.
     */
    public boolean isCorporateMemberAgreement(
            UUID applicationId,
            UUID agreementId
    ) {
        if (applicationId == null || agreementId == null) {
            return false;
        }

        final String sql = """
            SELECT EXISTS (
                SELECT 1
                FROM agreements.agreement a
                JOIN agreements.lu_agreement_classification ac
                  ON ac.agreement_classification_id =
                     a.agreement_classification_id
                 AND ac.is_active = TRUE
                WHERE a.application_id = :applicationId
                  AND a.agreement_id = :agreementId
                  AND a.is_active = TRUE
                  AND REPLACE(
                        REPLACE(
                            UPPER(
                                TRIM(
                                    COALESCE(
                                        NULLIF(ac.name, ''),
                                        ac.code
                                    )
                                )
                            ),
                            '-',
                            '_'
                        ),
                        ' ',
                        '_'
                      ) IN (
                        'CORP_INDIVIDUAL',
                        'CORPORATE_INDIVIDUAL'
                      )
            )
            """;

        Boolean result = jdbc.queryForObject(
                sql,
                new MapSqlParameterSource()
                        .addValue("applicationId", applicationId)
                        .addValue("agreementId", agreementId),
                Boolean.class
        );

        return Boolean.TRUE.equals(result);
    }

    public CorporateContext resolveCorporateContext(
            UUID applicationId,
            UUID memberClientRoleId,
            UUID agreementId
    ) {
        final String sql = """
            SELECT
                om.organization_id,
                oa.organization_account_id,
                COALESCE(
                    om.organization_contract_id,
                    oc.organization_contract_id
                ) AS organization_contract_id,
                o.client_role_id AS organization_client_role_id,
                COALESCE(oa.payment_terms_days, 0)
                    AS payment_terms_days
            FROM organization.organization_member om
            JOIN organization.organization o
              ON o.organization_id = om.organization_id
             AND o.application_id = om.application_id
             AND o.is_active = TRUE
            JOIN organization.organization_account oa
              ON oa.organization_id = om.organization_id
             AND oa.application_id = om.application_id
             AND oa.is_active = TRUE
             AND oa.on_account_enabled = TRUE
             AND oa.cau_blocked = FALSE
            LEFT JOIN organization.organization_contract oc
              ON oc.organization_id = om.organization_id
             AND oc.application_id = om.application_id
             AND oc.agreement_id = :agreementId
             AND oc.is_active = TRUE
            WHERE om.application_id = :applicationId
              AND om.client_role_id = :memberClientRoleId
              AND om.is_active = TRUE
              AND UPPER(TRIM(om.member_status_code)) = 'ACTIVE'
              AND (
                    om.effective_start_date IS NULL
                    OR om.effective_start_date <= CURRENT_DATE
                  )
              AND (
                    om.effective_end_date IS NULL
                    OR om.effective_end_date >= CURRENT_DATE
                  )
            ORDER BY
                CASE WHEN oa.is_default THEN 0 ELSE 1 END,
                oa.created_on
            LIMIT 1
            """;

        List<CorporateContext> rows = jdbc.query(
                sql,
                new MapSqlParameterSource()
                        .addValue("applicationId", applicationId)
                        .addValue(
                                "memberClientRoleId",
                                memberClientRoleId
                        )
                        .addValue("agreementId", agreementId),
                (rs, rowNum) -> new CorporateContext(
                        rs.getObject("organization_id", UUID.class),
                        rs.getObject(
                                "organization_account_id",
                                UUID.class
                        ),
                        rs.getObject(
                                "organization_contract_id",
                                UUID.class
                        ),
                        rs.getObject(
                                "organization_client_role_id",
                                UUID.class
                        ),
                        rs.getInt("payment_terms_days")
                )
        );

        if (rows.isEmpty()) {
            final String diagnosticSql = """
                SELECT
                    om.organization_member_id,
                    om.organization_id,
                    om.member_status_code,
                    om.is_active AS member_active,
                    om.effective_start_date,
                    om.effective_end_date,
                    o.is_active AS organization_active,
                    oa.organization_account_id,
                    oa.is_active AS account_active,
                    oa.on_account_enabled,
                    oa.cau_blocked
                FROM organization.organization_member om
                LEFT JOIN organization.organization o
                  ON o.organization_id = om.organization_id
                 AND o.application_id = om.application_id
                LEFT JOIN organization.organization_account oa
                  ON oa.organization_id = om.organization_id
                 AND oa.application_id = om.application_id
                WHERE om.application_id = :applicationId
                  AND om.client_role_id = :memberClientRoleId
                """;

            List<Map<String, Object>> diagnostics = jdbc.queryForList(
                    diagnosticSql,
                    new MapSqlParameterSource()
                            .addValue("applicationId", applicationId)
                            .addValue("memberClientRoleId", memberClientRoleId)
            );

            log.error(
                    "Corporate context resolution failed. applicationId={}, "
                            + "memberClientRoleId={}, agreementId={}, diagnostics={}",
                    applicationId,
                    memberClientRoleId,
                    agreementId,
                    diagnostics
            );

            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Corporate agreement selected, but no eligible active "
                            + "organization account was found for member "
                            + memberClientRoleId
            );
        }

        return rows.get(0);
    }

    public SplitRule resolveRule(
            UUID applicationId,
            UUID agreementVersionId,
            String paymentWhenCode
    ) {
        final String sql = """
            WITH eligible_group AS (
                SELECT
                    aga.agreement_group_id,
                    CASE
                        WHEN aga.allow_add_on = TRUE
                            THEN 'ADD_ON'
                        WHEN aga.allow_primary = TRUE
                            THEN 'PRIMARY'
                        ELSE NULL
                    END AS payment_role_code
                FROM agreements.agreement_group_agreement aga
                JOIN agreements.agreement_group ag
                  ON ag.agreement_group_id =
                     aga.agreement_group_id
                 AND ag.application_id =
                     aga.application_id
                 AND ag.is_active = TRUE
                WHERE aga.application_id = :applicationId
                  AND aga.agreement_version_id =
                      :agreementVersionId
                  AND aga.is_active = TRUE
                  AND (
                        aga.allow_add_on = TRUE
                        OR aga.allow_primary = TRUE
                      )
                ORDER BY
                    CASE
                        WHEN aga.allow_add_on = TRUE THEN 0
                        ELSE 1
                    END,
                    aga.created_on DESC
                LIMIT 1
            )
            SELECT
                pa.agreement_group_payment_allocation_id,
                pa.payment_role_id,
                pa.payment_when_id,
                pa.payment_allocation_type_id,
                UPPER(TRIM(pr.code)) AS payment_role_code,
                UPPER(TRIM(pw.code)) AS payment_when_code,
                UPPER(TRIM(pat.code)) AS allocation_type_code,
                pa.default_value AS member_percentage
            FROM eligible_group eg
            JOIN agreements.agreement_group_payment_allocation pa
              ON pa.agreement_group_id =
                 eg.agreement_group_id
             AND pa.application_id = :applicationId
             AND pa.is_active = TRUE
            JOIN agreements.lu_agreement_group_payment_role pr
              ON pr.payment_role_id = pa.payment_role_id
             AND pr.is_active = TRUE
             AND UPPER(TRIM(pr.code)) =
                 eg.payment_role_code
            JOIN agreements.lu_agreement_group_payment_when pw
              ON pw.payment_when_id = pa.payment_when_id
             AND pw.is_active = TRUE
             AND UPPER(TRIM(pw.code)) =
                 :paymentWhenCode
            JOIN agreements.lu_agreement_group_payment_allocation_type pat
              ON pat.payment_allocation_type_id =
                 pa.payment_allocation_type_id
             AND pat.is_active = TRUE
            ORDER BY
                COALESCE(pa.modified_on, pa.created_on) DESC
            LIMIT 1
            """;

        List<SplitRule> rows = jdbc.query(
                sql,
                new MapSqlParameterSource()
                        .addValue("applicationId", applicationId)
                        .addValue(
                                "agreementVersionId",
                                agreementVersionId
                        )
                        .addValue(
                                "paymentWhenCode",
                                normalize(paymentWhenCode)
                        ),
                (rs, rowNum) -> new SplitRule(
                        rs.getObject(
                                "agreement_group_payment_allocation_id",
                                UUID.class
                        ),
                        rs.getObject("payment_role_id", UUID.class),
                        rs.getObject("payment_when_id", UUID.class),
                        rs.getObject(
                                "payment_allocation_type_id",
                                UUID.class
                        ),
                        rs.getString("payment_role_code"),
                        rs.getString("payment_when_code"),
                        rs.getString("allocation_type_code"),
                        rs.getBigDecimal("member_percentage")
                )
        );

        return rows.isEmpty() ? null : rows.get(0);
    }

    /**
     * The POS payload currently contains the member portion.
     * Reverse the member percentage to recover the full amount.
     */
    public AmountSplit calculateFromMemberAmounts(
            BigDecimal memberSubtotal,
            BigDecimal memberDiscount,
            BigDecimal memberTax,
            BigDecimal memberTotal,
            SplitRule rule
    ) {
        if (rule == null) {
            throw new IllegalArgumentException(
                    "Corporate split rule is required"
            );
        }

        if (!"PERCENT".equals(
                normalize(rule.allocationTypeCode())
        )) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Only PERCENT corporate allocation is currently supported"
            );
        }

        BigDecimal memberPercentage =
                scale4(rule.memberPercentage());

        if (memberPercentage.compareTo(BigDecimal.ZERO) <= 0
                || memberPercentage.compareTo(HUNDRED) > 0) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Invalid member percentage: " + memberPercentage
            );
        }

        BigDecimal corporatePercentage =
                scale4(HUNDRED.subtract(memberPercentage));

        BigDecimal memberSub = money(memberSubtotal);
        BigDecimal memberDisc = money(memberDiscount);
        BigDecimal memberTaxValue = money(memberTax);
        BigDecimal memberTotalValue = money(memberTotal);

        BigDecimal fullSub =
                reversePercentage(memberSub, memberPercentage);

        BigDecimal fullDisc =
                reversePercentage(memberDisc, memberPercentage);

        BigDecimal fullTax =
                reversePercentage(memberTaxValue, memberPercentage);

        BigDecimal fullTotal = money(
                fullSub
                        .subtract(fullDisc)
                        .add(fullTax)
        );

        /*
         * Use the supplied member total to prevent a one-cent mismatch
         * caused by independent reverse calculations.
         */
        BigDecimal corporateTotal =
                money(fullTotal.subtract(memberTotalValue));

        BigDecimal corporateSub =
                money(fullSub.subtract(memberSub));

        BigDecimal corporateDisc =
                money(fullDisc.subtract(memberDisc));

        BigDecimal corporateTax =
                money(fullTax.subtract(memberTaxValue));

        return new AmountSplit(
                fullSub,
                fullDisc,
                fullTax,
                fullTotal,

                memberSub,
                memberDisc,
                memberTaxValue,
                memberTotalValue,

                corporateSub,
                corporateDisc,
                corporateTax,
                corporateTotal,

                memberPercentage,
                corporatePercentage
        );
    }

    public LocalDate calculateDueDate(CorporateContext context) {
        int terms = context == null
                ? 0
                : Math.max(context.paymentTermsDays(), 0);

        return LocalDate.now().plusDays(terms);
    }

    private static BigDecimal reversePercentage(
            BigDecimal memberAmount,
            BigDecimal memberPercentage
    ) {
        if (memberAmount == null
                || memberAmount.compareTo(BigDecimal.ZERO) == 0) {
            return BigDecimal.ZERO.setScale(2);
        }

        return memberAmount
                .multiply(HUNDRED)
                .divide(
                        memberPercentage,
                        2,
                        RoundingMode.HALF_UP
                );
    }

    private static BigDecimal money(BigDecimal value) {
        return value == null
                ? BigDecimal.ZERO.setScale(2)
                : value.setScale(2, RoundingMode.HALF_UP);
    }

    private static BigDecimal scale4(BigDecimal value) {
        return value == null
                ? BigDecimal.ZERO.setScale(4)
                : value.setScale(4, RoundingMode.HALF_UP);
    }

    private static String normalize(String value) {
        return value == null
                ? ""
                : value.trim().toUpperCase(Locale.ROOT);
    }
}