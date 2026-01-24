package io.clubone.transaction.controller;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;

@RestController
@RequestMapping("/notification")
public class InvoiceNotificationPayloadController {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @GetMapping("/invoice/{invoiceId}/email-payload")
    public ResponseEntity<NotificationRequestDTO> buildInvoiceEmailPayload(
            @PathVariable UUID invoiceId,

            // ✅ NEW: supports CASH/CARD/UPI etc.
            @RequestParam(defaultValue = "CARD") String paymentMethodType,

            // ✅ Optional (CASH won't have these)
            @RequestParam(required = false) String paymentMethodBrand,
            @RequestParam(required = false) String paymentLast4,
            @RequestParam(required = false) String authorizationCode,

            // optional
            @RequestParam(defaultValue = "INVOICE_PURCHASE_COMPLETED") String templateCode,
            @RequestParam(defaultValue = "EMAIL") String channel,
            @RequestParam(defaultValue = "ClubOne") String brandName,
            @RequestParam(defaultValue = "INR") String currencyCode,
            @RequestParam(required = false) String contractUrl,
            @RequestParam(required = false) String autoRenewalText
    ) {

        // 1) Invoice header
        InvoiceHeader header = fetchInvoiceHeader(invoiceId);

        // 2) Member ID (role_id)
        String memberId = fetchMemberId(header.clientRoleId);

        // 3) Email + name
        String toEmail = fetchBestEmail(header.clientRoleId);
        String clientName = fetchClientName(header.clientRoleId);

        // 4) Club details (invoice.level_id -> levels.reference_entity_id -> location)
        ClubInfo club = fetchClubInfo(header.levelId);

        // 5) Read all invoice entities once (we’ll derive contractName, packageName from it)
        List<InvoiceEntityRow> entities = fetchInvoiceEntities(invoiceId);

        // 6) Contract (agreement) name + package name from entity rows
        String contractName = resolveAgreementName(entities);
        String packageName  = resolvePackageName(entities);

        // 7) Line items (ITEM only) with ACCESS naming rule
        List<ItemInvoiceRow> itemRows = fetchItemInvoiceRows(invoiceId);
        List<SimpleLineItemDTO> lineItems = buildItemOnlyLineItemsWithAccessNaming(itemRows);
        boolean isAccess = itemRows.stream().anyMatch(r ->
        "ACCESS".equalsIgnoreCase(nullToEmpty(r.categoryCode).trim())
     || "ACCESS".equalsIgnoreCase(nullToEmpty(r.categoryName).trim())
);


        // ✅ Normalize payment fields for CASH vs CARD
        String pmType = safeUpper(paymentMethodType);

        String pmBrandOut = nullToEmpty(paymentMethodBrand);
        String pmLast4Out = nullToEmpty(paymentLast4);
        String authCodeOut = nullToEmpty(authorizationCode);

        if ("CASH".equals(pmType)) {
            pmBrandOut = "CASH";
            pmLast4Out = "";
            authCodeOut = "";
        }

        // 8) Build params
        Map<String, Object> params = new LinkedHashMap<>();

        //params.put("toEmail", toEmail);
        params.put("toEmail", "jane.doecs1994@gmail.com");
        params.put("clientName", clientName);

        // Order
        params.put("orderNumber", header.invoiceNumber);
        params.put("orderDate", formatInvoiceDate(header.invoiceDate));
        params.put("currencyCode", currencyCode);

        // Payment
        params.put("paymentMethodType", pmType);
        params.put("paymentMethodBrand", pmBrandOut);
        params.put("paymentLast4", pmLast4Out);
        params.put("authorizationCode", authCodeOut);

        // Membership / contract
        params.put("memberId", memberId);
        params.put("contractName", nullToEmpty(contractName));
        params.put("packageName", nullToEmpty(packageName));

        // Club
        params.put("clubName", club.clubName);
        params.put("clubAddress", club.clubAddress);

        // Items
        params.put("lineItems", lineItems);

        // Totals
        params.put("taxAmount", money(header.taxAmount));
        params.put("totalAmount", money(header.totalAmount));

        // Support
        params.put("supportLocationName", club.clubName);
        params.put("supportAddress", club.clubAddress);
        params.put("supportEmail", "concierge@clubone.io");
        params.put("supportPhone", "9988776655");

        // Contract url + legal
        params.put("contractUrl", contractUrl == null ? "" : contractUrl);
        params.put("autoRenewalText", autoRenewalText == null ? "" : autoRenewalText);

        params.put("brandName", brandName);
        params.put("year", String.valueOf(Year.now().getValue()));

        NotificationRequestDTO out = new NotificationRequestDTO();
        out.setClientId(header.clientRoleId);
        out.setChannel(Collections.singletonList(channel));
        out.setTemplateCode(templateCode);
        out.setParams(params);
        out.setAccess(isAccess);

        return ResponseEntity.ok(out);
    }

    private String safeUpper(String s) {
        return s == null ? "" : s.trim().toUpperCase(Locale.ENGLISH);
    }


    // ------------------------------------------------------------------------------------
    // Invoice / Client / Club
    // ------------------------------------------------------------------------------------

    private InvoiceHeader fetchInvoiceHeader(UUID invoiceId) {
        final String sql = """
            SELECT
                i.invoice_id,
                i.invoice_number,
                i.invoice_date,
                i.client_role_id,
                i.total_amount,
                i.tax_amount,
                i.level_id
            FROM transactions.invoice i
            WHERE i.invoice_id = ?
              AND i.is_active = true
            """;

        List<InvoiceHeader> rows = jdbcTemplate.query(sql, ps -> ps.setObject(1, invoiceId), (rs, rn) -> {
            InvoiceHeader h = new InvoiceHeader();
            h.invoiceId = (UUID) rs.getObject("invoice_id");
            h.invoiceNumber = rs.getString("invoice_number");
            h.invoiceDate = rs.getTimestamp("invoice_date");
            h.clientRoleId = (UUID) rs.getObject("client_role_id");
            h.totalAmount = rs.getBigDecimal("total_amount");
            h.taxAmount = rs.getBigDecimal("tax_amount");
            h.levelId = (UUID) rs.getObject("level_id");
            return h;
        });

        if (rows.isEmpty()) throw new IllegalArgumentException("Invoice not found for invoiceId=" + invoiceId);
        return rows.get(0);
    }

    private String fetchMemberId(UUID clientRoleId) {
        final String sql = """
            SELECT cr.role_id
            FROM clients.client_role cr
            WHERE cr.client_role_id = ?
              AND cr.is_active = true
            """;
        List<String> rows = jdbcTemplate.query(sql, ps -> ps.setObject(1, clientRoleId), (rs, rn) -> rs.getString(1));
        return rows.isEmpty() ? "" : nullToEmpty(rows.get(0));
    }

    private String fetchBestEmail(UUID clientRoleId) {
        final String sql = """
            SELECT e.email_address
            FROM clients.email_contact_mechanism e
            LEFT JOIN clients.email_contact_mechanism_purpose ep
                   ON ep.email_contact_mechanism_id = e.email_contact_mechanism_id
                  AND ep.is_active = true
            LEFT JOIN clients.email_contact_mechanism_purpose_type ept
                   ON ept.email_contact_mechanism_purpose_type_id = ep.email_contact_mechanism_purpose_type_id
                  AND ept.is_active = true
            WHERE e.client_role_id = ?
              AND e.is_active = true
              AND (e.valid_thru IS NULL OR e.valid_thru > now())
              AND (e.invalid IS NULL OR e.invalid = false)
            ORDER BY
              CASE WHEN UPPER(COALESCE(ept.name,'')) = 'PRIMARY' THEN 0 ELSE 1 END,
              e.valid_from DESC
            LIMIT 1
            """;

        List<String> rows = jdbcTemplate.query(sql, ps -> ps.setObject(1, clientRoleId), (rs, rn) -> rs.getString(1));
        return rows.isEmpty() ? "" : nullToEmpty(rows.get(0));
    }

    /**
     * Uses your characteristic type names:
     * Preferred Name -> First Name + Last Name -> First Name -> Customer
     */
    private String fetchClientName(UUID clientRoleId) {
        final String sql = """
            SELECT
                cct.name AS type_name,
                COALESCE(NULLIF(TRIM(cc.characteristic), ''), NULLIF(TRIM(ccv.value), '')) AS val
            FROM clients.client_characteristic cc
            JOIN clients.client_characteristic_type cct
              ON cct.client_characteristic_type_id = cc.client_characteristic_type_id
            LEFT JOIN clients.client_characteristic_values ccv
              ON ccv.client_characteristic_values_id = cc.client_characteristic_values_id
            WHERE cc.client_role_id = ?
              AND cc.is_active = true
              AND (cc.valid_thru IS NULL OR cc.valid_thru > now())
              AND cct.is_active = true
              AND cct.name IN ('Preferred Name', 'First Name', 'Last Name')
            ORDER BY cc.valid_from DESC
            """;

        Map<String, String> latest = new HashMap<>();
        jdbcTemplate.query(sql, ps -> ps.setObject(1, clientRoleId), rs -> {
            String type = rs.getString("type_name");
            String val  = rs.getString("val");
            if (type != null && val != null && !val.isBlank()) latest.putIfAbsent(type, val.trim());
        });

        String preferred = latest.getOrDefault("Preferred Name", "").trim();
        if (!preferred.isBlank()) return preferred;

        String first = latest.getOrDefault("First Name", "").trim();
        String last  = latest.getOrDefault("Last Name", "").trim();
        String full = (first + " " + last).trim();

        if (!full.isBlank()) return full;
        if (!first.isBlank()) return first;
        return "Customer";
    }

    private ClubInfo fetchClubInfo(UUID levelId) {
        if (levelId == null) return ClubInfo.empty();

        final String sql = """
            SELECT
              loc.display_name,
              loc.name,
              loc.address_line1,
              loc.address_line2,
              city.name AS city_name,
              loc.zip,
              loc.phone_number,
              loc.inquiry_email
            FROM locations.levels lvl
            JOIN locations.location loc
              ON loc.location_id = lvl.reference_entity_id
            LEFT JOIN locations.lu_city city
              ON city.city_id = loc.city_id
            WHERE lvl.level_id = ?
              AND loc.is_active = true
            """;

        List<ClubInfo> rows = jdbcTemplate.query(sql, ps -> ps.setObject(1, levelId), (rs, rn) -> {
            ClubInfo c = new ClubInfo();
            String displayName = rs.getString("display_name");
            String name = rs.getString("name");
            c.clubName = !isBlank(displayName) ? displayName : nullToEmpty(name);

            String a1 = nullToEmpty(rs.getString("address_line1"));
            String a2 = nullToEmpty(rs.getString("address_line2"));
            String city = nullToEmpty(rs.getString("city_name"));
            String zip = nullToEmpty(rs.getString("zip"));

            c.clubAddress = buildAddress(a1, a2, city, zip);
            c.phoneNumber = nullToEmpty(rs.getString("phone_number"));
            c.inquiryEmail = nullToEmpty(rs.getString("inquiry_email"));
            return c;
        });

        return rows.isEmpty() ? ClubInfo.empty() : rows.get(0);
    }

    // ------------------------------------------------------------------------------------
    // Invoice entities + enrichment
    // ------------------------------------------------------------------------------------

    private List<InvoiceEntityRow> fetchInvoiceEntities(UUID invoiceId) {
        final String sql = """
            SELECT
              ie.invoice_entity_id,
              ie.parent_invoice_entity_id,
              ie.entity_type_id,
              et.entity_type,
              ie.entity_id,
              ie.entity_description,
              COALESCE(ie.quantity, 1) AS qty,
              COALESCE(ie.unit_price, 0) AS unit_price,
              COALESCE(ie.discount_amount, 0) AS discount_amount,
              COALESCE(ie.tax_amount, 0) AS tax_amount,
              COALESCE(ie.total_amount, 0) AS total_amount
            FROM transactions.invoice_entity ie
            JOIN transactions.lu_entity_type et
              ON et.entity_type_id = ie.entity_type_id
            WHERE ie.invoice_id = ?
              AND ie.is_active = true
              AND (et.is_active IS NULL OR et.is_active = true)
            ORDER BY ie.created_on ASC
            """;

        return jdbcTemplate.query(sql, ps -> ps.setObject(1, invoiceId), (rs, rn) -> {
            InvoiceEntityRow r = new InvoiceEntityRow();
            r.invoiceEntityId = (UUID) rs.getObject("invoice_entity_id");
            r.parentInvoiceEntityId = (UUID) rs.getObject("parent_invoice_entity_id");
            r.entityType = rs.getString("entity_type");
            r.entityId = (UUID) rs.getObject("entity_id");
            r.entityDescription = rs.getString("entity_description");
            r.qty = rs.getInt("qty");
            r.unitPrice = rs.getBigDecimal("unit_price");
            r.discountAmount = rs.getBigDecimal("discount_amount");
            r.taxAmount = rs.getBigDecimal("tax_amount");
            r.totalAmount = rs.getBigDecimal("total_amount");
            return r;
        });
    }

    private String resolveAgreementName(List<InvoiceEntityRow> entities) {
        UUID agreementId = entities.stream()
                .filter(e -> e.parentInvoiceEntityId == null)
                .filter(e -> "AGREEMENT".equalsIgnoreCase(safeType(e.entityType)))
                .map(e -> e.entityId)
                .findFirst()
                .orElse(null);

        if (agreementId == null) return "";

        final String sql = """
            SELECT a.agreement_name
            FROM agreements.agreement a
            WHERE a.agreement_id = ?
              AND a.is_active = true
            """;

        List<String> rows = jdbcTemplate.query(sql, ps -> ps.setObject(1, agreementId), (rs, rn) -> rs.getString(1));
        return rows.isEmpty() ? "" : nullToEmpty(rows.get(0));
    }

    private String resolvePackageName(List<InvoiceEntityRow> entities) {
        UUID packageId = entities.stream()
                //.filter(e -> e.parentInvoiceEntityId == null)
                .filter(e -> "BUNDLE".equalsIgnoreCase(safeType(e.entityType)))
                .map(e -> e.entityId)
                .findFirst()
                .orElse(null);

        if (packageId == null) return "";

        final String sql = """
            SELECT p.package_name
            FROM package.package p
            WHERE p.package_id = ?
              AND p.is_active = true
            """;

        System.out.println("PackageId "+packageId);
        List<String> rows = jdbcTemplate.query(sql, ps -> ps.setObject(1, packageId), (rs, rn) -> rs.getString(1));
        return rows.isEmpty() ? "" : nullToEmpty(rows.get(0));
    }

    /**
     * For each invoice_entity row, if entity_type == ITEM, enrich from items.item.
     * Otherwise keep generic.
     */
    private List<SimpleLineItemDTO> buildItemOnlyLineItems(List<InvoiceEntityRow> entities) {

        List<SimpleLineItemDTO> out = new ArrayList<>();

        for (InvoiceEntityRow e : entities) {

            // ✅ Only ITEM rows
            if (!"ITEM".equalsIgnoreCase(safeType(e.entityType))) {
                continue;
            }

            SimpleLineItemDTO li = new SimpleLineItemDTO();

            // Description priority:
            // 1. invoice_entity.entity_description
            // 2. fallback text
            li.setDescription(
                (e.entityDescription != null && !e.entityDescription.isBlank())
                    ? e.entityDescription
                    : "Item"
            );

            li.setQuantity(e.qty);

            // Use total_amount directly (already includes tax/discount logic)
            li.setAmount(money(e.totalAmount));

            out.add(li);
        }

        return out;
    }


    private Map<UUID, ItemInfo> fetchItemsByIds(List<UUID> itemIds) {
        if (itemIds == null || itemIds.isEmpty()) return Collections.emptyMap();

        String placeholders = String.join(",", Collections.nCopies(itemIds.size(), "?"));

        String sql =
            "SELECT i.item_id, i.item_code, i.item_name, i.description " +
            "FROM items.item i " +
            "WHERE i.item_id IN (" + placeholders + ") " +
            "AND i.is_active = true";

        Object[] args = itemIds.toArray();

        List<ItemInfo> rows = jdbcTemplate.query(sql, args, (rs, rn) -> {
            ItemInfo ii = new ItemInfo();
            ii.itemId = (UUID) rs.getObject("item_id");
            ii.itemCode = rs.getString("item_code");
            ii.itemName = rs.getString("item_name");
            ii.description = rs.getString("description");
            return ii;
        });

        Map<UUID, ItemInfo> map = new HashMap<>();
        for (ItemInfo ii : rows) map.put(ii.itemId, ii);
        return map;
    }
    
    private List<SimpleLineItemDTO> buildItemOnlyLineItemsWithAccessNaming(List<ItemInvoiceRow> rows) {

        // Count occurrences per item_id to know duplicates
        Map<UUID, Integer> countByItem = new HashMap<>();
        for (ItemInvoiceRow r : rows) {
            countByItem.merge(r.itemId, 1, Integer::sum);
        }

        // Track index per item_id for "First/Second/Third..."
        Map<UUID, Integer> seqByItem = new HashMap<>();

        List<SimpleLineItemDTO> out = new ArrayList<>();

        for (ItemInvoiceRow r : rows) {
            SimpleLineItemDTO li = new SimpleLineItemDTO();
            li.setQuantity(r.quantity);
            li.setAmount(money(r.totalAmount));

            String itemName = (r.itemName == null || r.itemName.isBlank()) ? "Item" : r.itemName.trim();

            boolean isAccessCategory =
                    "ACCESS".equalsIgnoreCase(nullToEmpty(r.categoryCode).trim())
                    || "ACCESS".equalsIgnoreCase(nullToEmpty(r.categoryName).trim());

            boolean isDuplicateSameItem = countByItem.getOrDefault(r.itemId, 0) > 1;

            if (isAccessCategory && isDuplicateSameItem) {
                int n = seqByItem.merge(r.itemId, 1, Integer::sum); // 1..k
                li.setDescription(monthOrdinal(n) + " Month Fee");
            } else {
                li.setDescription(itemName);
            }

            out.add(li);
        }

        return out;
    }
    
    private List<ItemInvoiceRow> fetchItemInvoiceRows(UUID invoiceId) {
        final String sql = """
            SELECT
              ie.invoice_entity_id,
              ie.entity_id        AS item_id,
              ie.created_on       AS created_on,
              COALESCE(ie.quantity, 1) AS qty,
              COALESCE(ie.total_amount, 0) AS total_amount,

              i.item_name         AS item_name,
              i.item_code         AS item_code,

              cat.code            AS category_code,
              cat.name            AS category_name
            FROM transactions.invoice_entity ie
            JOIN transactions.lu_entity_type et
              ON et.entity_type_id = ie.entity_type_id
            JOIN items.item i
              ON i.item_id = ie.entity_id
            JOIN items.lu_item_category cat
              ON cat.item_category_id = i.item_category_id
            WHERE ie.invoice_id = ?
              AND ie.is_active = true
              AND UPPER(et.entity_type) = 'ITEM'
              AND i.is_active = true
              AND cat.is_active = true
            ORDER BY ie.created_on ASC
            """;

        return jdbcTemplate.query(sql, ps -> ps.setObject(1, invoiceId), (rs, rn) -> {
            ItemInvoiceRow r = new ItemInvoiceRow();
            r.invoiceEntityId = (UUID) rs.getObject("invoice_entity_id");
            r.itemId = (UUID) rs.getObject("item_id");
            r.createdOn = rs.getTimestamp("created_on");
            r.quantity = rs.getInt("qty");
            r.totalAmount = rs.getBigDecimal("total_amount");
            r.itemName = rs.getString("item_name");
            r.itemCode = rs.getString("item_code");
            r.categoryCode = rs.getString("category_code");
            r.categoryName = rs.getString("category_name");
            return r;
        });
    }


    private String monthOrdinal(int n) {
        return switch (n) {
            case 1 -> "First";
            case 2 -> "Second";
            case 3 -> "Third";
            case 4 -> "Fourth";
            case 5 -> "Fifth";
            case 6 -> "Sixth";
            case 7 -> "Seventh";
            case 8 -> "Eighth";
            case 9 -> "Ninth";
            case 10 -> "Tenth";
            case 11 -> "Eleventh";
            case 12 -> "Twelfth";
            default -> n + "th";
        };
    }


    private String safeType(String t) {
        return t == null ? "" : t.trim().toUpperCase(Locale.ENGLISH);
    }

    // ------------------------------------------------------------------------------------
    // Formatting helpers
    // ------------------------------------------------------------------------------------

    private String formatInvoiceDate(Timestamp ts) {
        if (ts == null) return "";
        DateTimeFormatter fmt = DateTimeFormatter.ofPattern("MMMM dd, yyyy", Locale.ENGLISH);
        LocalDate d = ts.toInstant().atZone(ZoneId.systemDefault()).toLocalDate();
        return fmt.format(d).toUpperCase(Locale.ENGLISH);
    }

    private String money(BigDecimal amt) {
        if (amt == null) amt = BigDecimal.ZERO;
        return amt.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    private String buildAddress(String a1, String a2, String city, String zip) {
        List<String> parts = new ArrayList<>();
        if (!isBlank(a1)) parts.add(a1);
        if (!isBlank(a2)) parts.add(a2);
        if (!isBlank(city)) parts.add(city);
        if (!isBlank(zip)) parts.add(zip);
        return String.join(", ", parts);
    }

    private boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }

    private String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

    // ------------------------------------------------------------------------------------
    // DTOs (same file)
    // ------------------------------------------------------------------------------------

    public static class NotificationRequestDTO {
        private UUID clientId; // clientRoleId
        private List<String> channel;
        private String templateCode;
        private Map<String, Object> params;
        private boolean isAccess;

        public UUID getClientId() { return clientId; }
        public void setClientId(UUID clientId) { this.clientId = clientId; }

        public List<String> getChannel() { return channel; }
        public void setChannel(List<String> channel) { this.channel = channel; }

        public String getTemplateCode() { return templateCode; }
        public void setTemplateCode(String templateCode) { this.templateCode = templateCode; }

        public Map<String, Object> getParams() { return params; }
        public void setParams(Map<String, Object> params) { this.params = params; }
		public boolean isAccess() {
			return isAccess;
		}
		public void setAccess(boolean isAccess) {
			this.isAccess = isAccess;
		}
        
        
    }

    public static class InvoiceLineItemDTO {
        // generic
        private String entityType;
        private UUID entityId;

        // display
        private String description;

        // item enrichment
        private String itemCode;
        private String itemName;
        private String itemDescription;

        // amounts
        private Integer quantity;
        private String unitPrice;
        private String discountAmount;
        private String taxAmount;
        private String totalAmount;

        public String getEntityType() { return entityType; }
        public void setEntityType(String entityType) { this.entityType = entityType; }

        public UUID getEntityId() { return entityId; }
        public void setEntityId(UUID entityId) { this.entityId = entityId; }

        public String getDescription() { return description; }
        public void setDescription(String description) { this.description = description; }

        public String getItemCode() { return itemCode; }
        public void setItemCode(String itemCode) { this.itemCode = itemCode; }

        public String getItemName() { return itemName; }
        public void setItemName(String itemName) { this.itemName = itemName; }

        public String getItemDescription() { return itemDescription; }
        public void setItemDescription(String itemDescription) { this.itemDescription = itemDescription; }

        public Integer getQuantity() { return quantity; }
        public void setQuantity(Integer quantity) { this.quantity = quantity; }

        public String getUnitPrice() { return unitPrice; }
        public void setUnitPrice(String unitPrice) { this.unitPrice = unitPrice; }

        public String getDiscountAmount() { return discountAmount; }
        public void setDiscountAmount(String discountAmount) { this.discountAmount = discountAmount; }

        public String getTaxAmount() { return taxAmount; }
        public void setTaxAmount(String taxAmount) { this.taxAmount = taxAmount; }

        public String getTotalAmount() { return totalAmount; }
        public void setTotalAmount(String totalAmount) { this.totalAmount = totalAmount; }
    }

    private static class InvoiceHeader {
        UUID invoiceId;
        String invoiceNumber;
        Timestamp invoiceDate;
        UUID clientRoleId;
        BigDecimal totalAmount;
        BigDecimal taxAmount;
        UUID levelId;
    }

    private static class ClubInfo {
        String clubName;
        String clubAddress;
        String phoneNumber;
        String inquiryEmail;

        static ClubInfo empty() {
            ClubInfo c = new ClubInfo();
            c.clubName = "";
            c.clubAddress = "";
            c.phoneNumber = "";
            c.inquiryEmail = "";
            return c;
        }
    }
    
    
    private static class ItemInvoiceRow {
        UUID invoiceEntityId;
        UUID itemId;
        Timestamp createdOn;

        Integer quantity;
        BigDecimal totalAmount;

        String itemName;
        String itemCode;

        String categoryCode;
        String categoryName;
    }


    public static class SimpleLineItemDTO {
        private String description;
        private Integer quantity;
        private String amount;

        public String getDescription() { return description; }
        public void setDescription(String description) { this.description = description; }

        public Integer getQuantity() { return quantity; }
        public void setQuantity(Integer quantity) { this.quantity = quantity; }

        public String getAmount() { return amount; }
        public void setAmount(String amount) { this.amount = amount; }
    }

    private static class InvoiceEntityRow {
        UUID invoiceEntityId;
        UUID parentInvoiceEntityId;

        String entityType; // from lu_entity_type.entity_type
        UUID entityId;

        String entityDescription;

        int qty;
        BigDecimal unitPrice;
        BigDecimal discountAmount;
        BigDecimal taxAmount;
        BigDecimal totalAmount;
    }

    private static class ItemInfo {
        UUID itemId;
        String itemCode;
        String itemName;
        String description;
    }
}


