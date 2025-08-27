package io.clubone.transaction.v2.vo;

import lombok.Data;
import java.time.LocalDate;
import java.util.UUID;

@Data
public class InvoiceEntityPlanRow {
    private UUID invoiceEntityId;
    private UUID entityId;
    private UUID entityTypeId;

    private LocalDate contractStartDate;
    private LocalDate contractEndDate;

    private UUID createdBy;

    // From bundles_new.bundle_plan_template
    private UUID subscriptionFrequencyId;
    private UUID subscriptionBillingDayRuleId;
    private Integer intervalCount; // default/nullable; set to 1 in mapper if null
}

