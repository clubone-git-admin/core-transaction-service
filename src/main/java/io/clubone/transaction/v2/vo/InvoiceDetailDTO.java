package io.clubone.transaction.v2.vo;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record InvoiceDetailDTO(

        // =====================================================
        // Invoice header
        // =====================================================

        UUID invoiceId,
        String invoiceNumber,
        LocalDate invoiceDate,
        String status,
        BigDecimal amount,
        BigDecimal balanceDue,
        BigDecimal writeOff,
        String salesRep,

        // =====================================================
        // Summary strip
        // =====================================================

        String contractBadge,
        String subscriptionStatusBadge,
        String title,
        String subtitle,
        BigDecimal amountPerMonth,

        // =====================================================
        // Information cards
        // =====================================================

        Integer commitmentPaidNumerator,
        Integer commitmentPaidDenominator,
        String billingFrequencyLabel,
        LocalDate contractEnd,
        LocalDate nextBillingDate,
        LocalDate startDate,
        LocalDate signUpDate,
        Boolean autoPay,
        String primaryPaymentMethodMasked,

        // =====================================================
        // Product strip
        // =====================================================

        String productLabel,
        Boolean recurring,
        String availableSessionsLabel,
        String sessionOwner,

        // =====================================================
        // Payment timeline
        // =====================================================

        List<PaymentTimelineItemDTO> paymentTimeline,

        // =====================================================
        // Transaction details
        // FE: ClientInvoiceTransaction
        // JSON: transactions
        // =====================================================

        List<InvoiceTransactionDetailDTO> transactions,

        // =====================================================
        // Refund details
        // FE: ClientInvoiceRefund
        // JSON: refunds
        // =====================================================

        List<InvoiceRefundDetailDTO> refunds,

        // =====================================================
        // Refund allocations
        // FE: ClientInvoiceRefundAllocation
        // JSON: refundAllocations
        // =====================================================

        List<InvoiceRefundAllocationDTO> refundAllocations,

        // =====================================================
        // Billing adjustments
        // FE: ClientInvoiceAdjustment
        // JSON: adjustments
        // =====================================================

        List<InvoiceAdjustmentDetailDTO> adjustments

) {

    /*
     * Prevent null collection values in the API response.
     *
     * The frontend can safely iterate over:
     * - paymentTimeline
     * - transactions
     * - refunds
     * - refundAllocations
     * - adjustments
     */
    public InvoiceDetailDTO {
        paymentTimeline = paymentTimeline == null
                ? List.of()
                : List.copyOf(paymentTimeline);

        transactions = transactions == null
                ? List.of()
                : List.copyOf(transactions);

        refunds = refunds == null
                ? List.of()
                : List.copyOf(refunds);

        refundAllocations = refundAllocations == null
                ? List.of()
                : List.copyOf(refundAllocations);

        adjustments = adjustments == null
                ? List.of()
                : List.copyOf(adjustments);
    }
}