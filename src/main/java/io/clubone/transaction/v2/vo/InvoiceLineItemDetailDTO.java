package io.clubone.transaction.v2.vo;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Invoice line item shaped for the member invoice-detail UI
 * ({@code ClientInvoiceItem} on the Flutter side).
 */
public record InvoiceLineItemDetailDTO(
		UUID invoiceEntityId,
		UUID parentInvoiceEntityId,
		String entityType,
		UUID entityId,
		String entityDescription,
		String chargeLineKindCode,
		String chargeLineKindName,
		UUID planTemplateId,
		String planCode,
		String planName,
		UUID billingScheduleId,
		UUID subscriptionInstanceId,
		Integer cycleNumber,
		LocalDate servicePeriodStart,
		LocalDate servicePeriodEnd,
		int quantity,
		BigDecimal unitPrice,
		BigDecimal discountAmount,
		BigDecimal taxAmount,
		BigDecimal totalAmount
) {
}
