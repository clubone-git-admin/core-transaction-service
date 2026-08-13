package io.clubone.transaction.v2.vo;

import java.time.Instant;

/**
 * Lightweight audit event for the member invoice-detail Audit tab.
 */
public record InvoiceAuditDetailDTO(
		String eventType,
		String title,
		String description,
		String actor,
		Instant eventOn
) {
}
