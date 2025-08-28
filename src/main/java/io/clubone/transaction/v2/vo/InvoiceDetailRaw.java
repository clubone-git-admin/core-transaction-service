package io.clubone.transaction.v2.vo;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

public record InvoiceDetailRaw(
	    // invoice
	    UUID invoiceId,
	    String invoiceNumber,
	    LocalDate invoiceDate,
	    BigDecimal invoiceAmount,
	    BigDecimal invoiceBalanceDue,
	    BigDecimal invoiceWriteOff,
	    String invoiceStatus,
	    String salesRep,
	    UUID levelId,

	    // billing history (picked)
	    UUID subscriptionInstanceId,
	    BigDecimal amountGrossInclTax,

	    // subscription instance
	    UUID subscriptionPlanId,
	    LocalDate instanceStartDate,
	    LocalDate instanceNextBillingDate,
	    LocalDate instanceLastBilledOn,
	    LocalDate instanceEndDate,
	    Integer currentCycleNumber,

	    // plan
	    Integer intervalCount,
	    UUID subscriptionFrequencyId,
	    LocalDate contractStartDate,
	    LocalDate contractEndDate,
	    UUID entityId,          // plan entity_id
	    UUID entityTypeId,      // plan entity_type_id

	    // frequency/terms
	    String frequencyName,
	    Integer remainingCycles,

	    // invoice_entity joins
	    UUID childEntityId,
	    UUID childEntityTypeId,
	    UUID parentEntityId,
	    UUID parentEntityTypeId,

	    // template
	    Integer templateTotalCycles,
	    UUID templateLevelId,

	    // final cycles
	    Integer totalCycles
	) {}
