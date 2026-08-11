package io.clubone.transaction.vo;

import java.math.BigDecimal;
import java.util.UUID;

public record GiftcardPurchaseLineRow(
        UUID invoiceEntityId,
        UUID invoiceId,
        UUID applicationId,
        UUID itemId,
        UUID itemVersionId,
        UUID cfgGiftcardId,
        UUID giftcardTypeId,
        int quantity,
        BigDecimal faceValue,
        BigDecimal discountAmount,
        BigDecimal taxAmount,
        BigDecimal lineTotal,
        String currencyCode,
        UUID purchaserClientRoleId,
        UUID purchaseLevelId,
        String activationModeCode,
        Integer validForValue,
        String validForUnitCode,
        boolean restrictToBuyer,
        boolean reloadable,
        boolean allowPartialRedemption,
        boolean allowSplitTender,
        boolean allowRecurringPayment,
        boolean allowGiftcardPurchase,
        boolean allowTax,
        boolean allowFees,
        boolean allowTips,
        boolean allowCashOut,
        String locationModeCode,
        Long maxBalanceMinor
) {
    public String applicationScopedKey(int sequence) {
        return applicationId + ":" + invoiceId + ":" + invoiceEntityId + ":" + sequence;
    }
}