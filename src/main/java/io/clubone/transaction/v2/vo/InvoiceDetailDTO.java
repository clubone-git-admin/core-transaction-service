package io.clubone.transaction.v2.vo;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record InvoiceDetailDTO(

        // invoice header
        UUID invoiceId,
        String invoiceNumber,
        LocalDate invoiceDate,
        String status,
        BigDecimal amount,
        BigDecimal balanceDue,
        BigDecimal writeOff,
        String salesRep,

        // summary strip
        String contractBadge,            // e.g., "CONTRACT"
        String subscriptionStatusBadge,  // e.g., "ACTIVE"
        String title,                    // e.g., "All Access Fitness"
        String subtitle,                 // e.g., "Flatiron · SUB10238"
        BigDecimal amountPerMonth,       // effective price for the current cycle

        // info cards
        Integer commitmentPaidNumerator,     // current cycle number
        Integer commitmentPaidDenominator,   // current + remaining (if available)
        String billingFrequencyLabel,        // "Monthly on the 22nd"
        LocalDate contractEnd,
        LocalDate nextBillingDate,
        LocalDate startDate,
        LocalDate signUpDate,                // optional (can be null)
        Boolean autoPay,                     // optional (can be null)
        String primaryPaymentMethodMasked,   // optional (can be null)

        // product strip
        String productLabel,                 // "Membership · Base Membership"
        Boolean recurring,                   // true/false
        String availableSessionsLabel,       // "—" or number-as-string
        String sessionOwner,                 // optional

        // timeline
        List<PaymentTimelineItemDTO> paymentTimeline
) {}

