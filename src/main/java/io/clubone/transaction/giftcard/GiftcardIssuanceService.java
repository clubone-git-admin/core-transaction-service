package io.clubone.transaction.giftcard;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.clubone.transaction.dao.TransactionDAO;
import io.clubone.transaction.response.GiftcardIssuedDTO;
import io.clubone.transaction.vo.GiftcardPurchaseLineRow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

@Service
public class GiftcardIssuanceService {

    private static final Logger log = LoggerFactory.getLogger(GiftcardIssuanceService.class);
    private static final char[] TOKEN_ALPHABET = "23456789ABCDEFGHJKLMNPQRSTUVWXYZ".toCharArray();

    private final TransactionDAO transactionDAO;
    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final byte[] tokenSecret;

    public GiftcardIssuanceService(
            TransactionDAO transactionDAO,
            @Qualifier("cluboneJdbcTemplate")
            JdbcTemplate cluboneJdbcTemplate,
            ObjectMapper objectMapper,
            @Value("${giftcard.token-secret}") String tokenSecret
    ) {
        if (!StringUtils.hasText(tokenSecret) || tokenSecret.length() < 32) {
            throw new IllegalStateException("giftcard.token-secret must contain at least 32 characters");
        }
        this.transactionDAO = transactionDAO;
        this.jdbc = cluboneJdbcTemplate;
        this.objectMapper = objectMapper;
        this.tokenSecret = tokenSecret.getBytes(StandardCharsets.UTF_8);
    }

    public List<GiftcardIssuedDTO> issueForPaidInvoice(
            UUID invoiceId,
            UUID clientPaymentTransactionId,
            UUID applicationId,
            UUID actorId
    ) {
        long startedAt = System.nanoTime();
        List<GiftcardPurchaseLineRow> lines =
                transactionDAO.findGiftcardPurchaseLines(invoiceId, applicationId);

        if (lines.isEmpty()) {
            return List.of();
        }

        List<GiftcardIssuedDTO> issued = new ArrayList<>();
        for (GiftcardPurchaseLineRow line : lines) {
            validate(line);
            for (int sequence = 1; sequence <= line.quantity(); sequence++) {
                issued.add(issueOne(line, sequence, clientPaymentTransactionId, actorId));
            }
        }

        log.info(
                "Gift Cards issued: invoiceId={}, lineCount={}, cardCount={}, elapsedMs={}",
                invoiceId,
                lines.size(),
                issued.size(),
                (System.nanoTime() - startedAt) / 1_000_000L
        );
        return List.copyOf(issued);
    }

    private GiftcardIssuedDTO issueOne(
            GiftcardPurchaseLineRow line,
            int sequence,
            UUID clientPaymentTransactionId,
            UUID actorId
    ) {
        String cardNumber = deterministicCardNumber(line.applicationScopedKey(sequence));
        String cardLast4 = cardNumber.substring(cardNumber.length() - 4);
        String tokenHash = sha256Hex(cardNumber);
        long amountMinor = line.faceValue()
                .movePointRight(2)
                .setScale(0, RoundingMode.UNNECESSARY)
                .longValueExact();

        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        boolean activateNow = "ON_ISSUE".equalsIgnoreCase(line.activationModeCode())
                || "ON_PAYMENT_CAPTURE".equalsIgnoreCase(line.activationModeCode());
        String status = activateNow ? "ACTIVE" : "PENDING_ACTIVATION";
        OffsetDateTime activatedOn = activateNow ? now : null;
        OffsetDateTime expiryBase = activateNow ? activatedOn : now;
        OffsetDateTime expiresOn = calculateExpiry(
                expiryBase,
                line.validForValue(),
                line.validForUnitCode()
        );

        UUID accountId = UUID.randomUUID();
        int inserted = jdbc.update(
                """
                INSERT INTO client_giftcard.giftcard_account (
                    client_giftcard_id, application_id,
                    source_item_id, source_item_version_id, source_cfg_giftcard_id,
                    purchase_invoice_id, purchase_invoice_entity_id, purchase_unit_sequence,
                    purchase_location_id, purchaser_client_role_id, owner_client_role_id,
                    card_token_hash, card_last4, currency_code,
                    original_amount_minor, current_balance_minor, reserved_balance_minor,
                    status_code, issued_on, activated_on, expires_on, last_activity_on,
                    policy_snapshot, row_version, is_active, created_by, created_on
                ) VALUES (
                    ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0,
                    ?, ?, ?, ?, ?, ?::jsonb, 0, TRUE, ?, ?
                )
                ON CONFLICT (application_id, purchase_invoice_entity_id, purchase_unit_sequence)
                WHERE purchase_invoice_entity_id IS NOT NULL
                  AND purchase_unit_sequence IS NOT NULL
                DO NOTHING
                """,
                accountId,
                line.applicationId(),
                line.itemId(),
                line.itemVersionId(),
                line.cfgGiftcardId(),
                line.invoiceId(),
                line.invoiceEntityId(),
                sequence,
                line.purchaseLevelId(),
                line.purchaserClientRoleId(),
                line.purchaserClientRoleId(),
                tokenHash,
                cardLast4,
                line.currencyCode().trim().toUpperCase(Locale.ROOT),
                amountMinor,
                amountMinor,
                status,
                now,
                activatedOn,
                expiresOn,
                now,
                policySnapshot(line),
                actorId,
                now
        );

        if (inserted == 0) {
            return loadExisting(line, sequence, cardNumber);
        }

        jdbc.update(
                """
                INSERT INTO client_giftcard.giftcard_transaction (
                    giftcard_transaction_id, client_giftcard_id, application_id,
                    transaction_type_code, amount_minor,
                    balance_before_minor, balance_after_minor,
                    currency_code, invoice_id, client_payment_transaction_id,
                    location_id, idempotency_key, reason_code, metadata,
                    occurred_on, created_by
                ) VALUES (
                    gen_random_uuid(), ?, ?, 'ISSUE', ?, 0, ?, ?, ?, ?, ?, ?,
                    'PURCHASE_FINALIZED', '{}'::jsonb, ?, ?
                )
                """,
                accountId,
                line.applicationId(),
                amountMinor,
                amountMinor,
                line.currencyCode().trim().toUpperCase(Locale.ROOT),
                line.invoiceId(),
                clientPaymentTransactionId,
                line.purchaseLevelId(),
                issueIdempotencyKey(line, sequence),
                now,
                actorId
        );

        return new GiftcardIssuedDTO(
                accountId, cardNumber, cardLast4, amountMinor,
                line.currencyCode().trim().toUpperCase(Locale.ROOT),
                status, expiresOn
        );
    }

    private GiftcardIssuedDTO loadExisting(
            GiftcardPurchaseLineRow line,
            int sequence,
            String cardNumber
    ) {
        return jdbc.queryForObject(
                """
                SELECT client_giftcard_id, card_last4, current_balance_minor,
                       currency_code, status_code, expires_on
                FROM client_giftcard.giftcard_account
                WHERE application_id = ?
                  AND purchase_invoice_entity_id = ?
                  AND purchase_unit_sequence = ?
                """,
                (rs, rowNum) -> new GiftcardIssuedDTO(
                        rs.getObject("client_giftcard_id", UUID.class),
                        cardNumber,
                        rs.getString("card_last4"),
                        rs.getLong("current_balance_minor"),
                        rs.getString("currency_code"),
                        rs.getString("status_code"),
                        rs.getObject("expires_on", OffsetDateTime.class)
                ),
                line.applicationId(), line.invoiceEntityId(), sequence
        );
    }

    private void validate(GiftcardPurchaseLineRow line) {
        if (line.itemVersionId() == null || line.cfgGiftcardId() == null) {
            throw new IllegalStateException("Gift Card configuration/version missing for invoiceEntityId="
                    + line.invoiceEntityId());
        }
        if (line.quantity() <= 0 || line.faceValue() == null || line.faceValue().signum() <= 0) {
            throw new IllegalStateException("Invalid Gift Card quantity/value for invoiceEntityId="
                    + line.invoiceEntityId());
        }
        if (line.currencyCode() == null ||
                !line.currencyCode().trim().matches("^[A-Z]{3}$")) {
            throw new IllegalStateException("Invalid Gift Card currency for invoiceId=" + line.invoiceId());
        }
        long amountMinor = line.faceValue().movePointRight(2)
                .setScale(0, RoundingMode.UNNECESSARY).longValueExact();
        if (line.maxBalanceMinor() != null && amountMinor > line.maxBalanceMinor()) {
            throw new IllegalStateException("Gift Card value exceeds max balance for invoiceEntityId="
                    + line.invoiceEntityId());
        }
    }

    private OffsetDateTime calculateExpiry(OffsetDateTime base, Integer value, String unit) {
        if (value == null && unit == null) return null;
        if (value == null || value <= 0 || !StringUtils.hasText(unit)) {
            throw new IllegalStateException("Gift Card validity value/unit is incomplete");
        }
        return switch (unit.trim().toUpperCase(Locale.ROOT)) {
            case "DAY", "DAYS" -> base.plusDays(value);
            case "WEEK", "WEEKS" -> base.plusWeeks(value);
            case "MONTH", "MONTHS" -> base.plusMonths(value);
            case "YEAR", "YEARS" -> base.plusYears(value);
            default -> throw new IllegalStateException("Unsupported Gift Card validity unit: " + unit);
        };
    }

    private String policySnapshot(GiftcardPurchaseLineRow line) {
        Map<String, Object> policy = new LinkedHashMap<>();
        policy.put("cfgGiftcardId", line.cfgGiftcardId());
        policy.put("giftcardTypeId", line.giftcardTypeId());
        policy.put("activationModeCode", line.activationModeCode());
        policy.put("validForValue", line.validForValue());
        policy.put("validForUnitCode", line.validForUnitCode());
        policy.put("restrictToBuyer", line.restrictToBuyer());
        policy.put("isReloadable", line.reloadable());
        policy.put("allowPartialRedemption", line.allowPartialRedemption());
        policy.put("allowSplitTender", line.allowSplitTender());
        policy.put("allowRecurringPayment", line.allowRecurringPayment());
        policy.put("allowGiftcardPurchase", line.allowGiftcardPurchase());
        policy.put("allowTax", line.allowTax());
        policy.put("allowFees", line.allowFees());
        policy.put("allowTips", line.allowTips());
        policy.put("allowCashOut", line.allowCashOut());
        policy.put("locationModeCode", line.locationModeCode());
        policy.put("maxBalanceMinor", line.maxBalanceMinor());
        try {
            return objectMapper.writeValueAsString(policy);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Unable to create Gift Card policy snapshot", exception);
        }
    }

    private String deterministicCardNumber(String source) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(tokenSecret, "HmacSHA256"));
            byte[] digest = mac.doFinal(source.getBytes(StandardCharsets.UTF_8));
            StringBuilder value = new StringBuilder("GC");
            for (int i = 0; i < 20; i++) {
                value.append(TOKEN_ALPHABET[Byte.toUnsignedInt(digest[i]) % TOKEN_ALPHABET.length]);
            }
            return value.toString();
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to generate Gift Card number", exception);
        }
    }

    private String sha256Hex(String value) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256")
                            .digest(value.getBytes(StandardCharsets.UTF_8))
            );
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to hash Gift Card number", exception);
        }
    }

    private String issueIdempotencyKey(GiftcardPurchaseLineRow line, int sequence) {
        return "GC_ISSUE:" + line.invoiceId() + ':' + line.invoiceEntityId() + ':' + sequence;
    }
}