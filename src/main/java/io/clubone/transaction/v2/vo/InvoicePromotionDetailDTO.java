package io.clubone.transaction.v2.vo;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Promotion applied to an invoice line, shaped for the member invoice-detail UI
 * ({@code ClientInvoicePromotion} on the Flutter side).
 */
public record InvoicePromotionDetailDTO(
		UUID invoiceEntityPromotionId,
		UUID invoiceEntityId,
		UUID promotionVersionId,
		String promotionName,
		BigDecimal promotionAmount,
		UUID promotionApplicabilityId,
		UUID promotionEffectId
) {
}
