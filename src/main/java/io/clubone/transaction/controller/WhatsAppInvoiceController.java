package io.clubone.transaction.controller;

import java.time.Instant;
import java.util.*;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.reactive.function.client.*;
import org.springframework.web.server.ResponseStatusException;

import reactor.core.publisher.Mono;

/**
 * ✅ SINGLE FILE:
 *   1) Invoice PDF WhatsApp
 *   2) Generic Notification WhatsApp (templates from application.properties + member list)
 *
 * Existing:
 *   POST /api/whatsapp/invoice/send (multipart)
 *
 * New:
 *   POST /api/whatsapp/notify/send (json)
 *     - templateName
 *     - members: [{firstName,lastName,phoneNumber,variables?}]
 *     - variables?: global vars
 *     - dryRun?: true -> doesn't send
 *     - throttleMs?: delay between sends
 */
@RestController
@RequestMapping("/api/whatsapp")
public class WhatsAppInvoiceController {

  // =========================
  // Config
  // =========================
  @Value("${whatsapp.graphBaseUrl:https://graph.facebook.com/v22.0}")
  private String graphBaseUrl;

  @Value("${whatsapp.phoneNumberId:1006971872489087}")
  private String phoneNumberId;

  @Value("${whatsapp.accessToken:EAAMN5mUdePkBQZBSqDt68UnAMFqas0MFfPrBY0PtyV29vjVqRaBHTUJ9ujxTz1EtuurgvSZAhaj1zZCcZArTSs9IZC9nBZAppiFks2v8TSVoWyiVDvYfWeMr15iR5DZCa8VRywNKlxwbfCBsJKxRnad9bAOZBZAllnhXEosvsw3vvPz0mK2XflDZBofm0U8aKn7ZARa8XvZBUVI3ACoi4bfco4EvPqlTzK2U8JMCp5IS2HgkzAX3nt0vzgLb175wseT1Pj5LbNLA7VayZBSlqYQhwJ6o1}")
  private String accessToken;

  /**
   * Templates Map from application.properties
   *
   * whatsapp.template={ \
   *   EXPIRING_MEMBER:'Hi {firstName} {lastName}, your membership is expiring soon at {clubName}.', \
   *   LEAST_CHECKIN_MEMBER:'Hi {firstName}, we miss you! You visited only {visits} times recently.', \
   *   GENERAL:'Hi {firstName}, {message}' \
   * }
   */
  @Value("#{${whatsapp.template:{}}}")
  private Map<String, String> whatsappTemplates;

  // Optional: DB logging
  private final JdbcTemplate cluboneJdbcTemplate;

  // WebClient
  private final WebClient webClient;

  public WhatsAppInvoiceController(
      WebClient.Builder webClientBuilder,
      JdbcTemplate cluboneJdbcTemplate
  ) {
    this.webClient = webClientBuilder
        .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
        .build();
    this.cluboneJdbcTemplate = cluboneJdbcTemplate;
  }

  // =========================
  // EXISTING API: Invoice PDF
  // =========================
  @PostMapping(
      value = "/invoice/send",
      consumes = MediaType.MULTIPART_FORM_DATA_VALUE,
      produces = MediaType.APPLICATION_JSON_VALUE
  )
  public ResponseEntity<SendInvoiceWhatsAppResponse> sendInvoicePdf(
      @RequestParam("to") String to,
      @RequestParam("invoiceId") String invoiceId,
      @RequestParam(value = "caption", required = false) String caption,
      @RequestPart("pdf") MultipartFile pdf
  ) {
    if (to == null || to.trim().isEmpty()) throw badRequest("'to' is required");
    if (invoiceId == null || invoiceId.trim().isEmpty()) throw badRequest("'invoiceId' is required");
    if (pdf == null || pdf.isEmpty()) throw badRequest("'pdf' is required");
    if (!isPdf(pdf)) throw badRequest("'pdf' must be application/pdf");

    final String toDigits = normalizeToDigits(to);
    if (toDigits.length() < 10) throw badRequest("Invalid WhatsApp number format in 'to'");

    final String finalCaption = (caption == null || caption.trim().isEmpty())
        ? ("Invoice " + invoiceId)
        : caption.trim();

    final String traceId = UUID.randomUUID().toString();
    final Instant now = Instant.now();

    try {
      final String mediaId = uploadMedia(pdf, traceId);

      // optional text
      sendTextOrThrow(toDigits, "Invoice created " + invoiceId);

      final Map<String, Object> waResp = sendDocument(toDigits, mediaId, invoiceId, finalCaption, traceId);

      safePersistTrace(toDigits, invoiceId, mediaId, traceId, waResp, now);

      return ResponseEntity.ok(new SendInvoiceWhatsAppResponse(
          "SENT",
          invoiceId,
          toDigits,
          mediaId,
          traceId,
          now.toString(),
          waResp
      ));

    } catch (WebClientResponseException wex) {
      safePersistFailure(toDigits, invoiceId, traceId, wex, now);
      throw new ResponseStatusException(
          HttpStatus.BAD_GATEWAY,
          "WhatsApp API error: " + wex.getStatusCode() + " body=" + safeBody(wex),
          wex
      );
    } catch (Exception ex) {
      safePersistFailure(toDigits, invoiceId, traceId, ex, now);
      throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Failed to send invoice PDF on WhatsApp", ex);
    }
  }

  // =========================
  // ✅ NEW API: Notification Send (templateName + members[])
  // =========================
  @PostMapping(
      value = "/notify/send",
      consumes = MediaType.APPLICATION_JSON_VALUE,
      produces = MediaType.APPLICATION_JSON_VALUE
  )
  public ResponseEntity<BulkNotifyResponse> sendNotificationToMembers(@RequestBody NotifySendRequest req) {

    if (req == null) throw badRequest("Request body is required");
    if (isBlank(req.templateName)) throw badRequest("'templateName' is required");
    if (req.members == null || req.members.isEmpty()) throw badRequest("'members' is required");

    final String templateKey = req.templateName.trim();
    final String templateBody = (whatsappTemplates == null) ? null : whatsappTemplates.get(templateKey);

    if (isBlank(templateBody)) {
      throw badRequest("Template not found in application.properties for templateName=" + templateKey);
    }

    final boolean dryRun = Boolean.TRUE.equals(req.dryRun);
    final long throttleMs = (req.throttleMs == null || req.throttleMs < 0) ? 0L : req.throttleMs;

    final String bulkTraceId = UUID.randomUUID().toString();
    final Instant now = Instant.now();

    int sent = 0;
    int failed = 0;

    final Map<String, String> globalVars = (req.variables == null) ? Map.of() : req.variables;
    final List<MemberSendResult> results = new ArrayList<>();

    for (MemberRef m : req.members) {
      final String rawPhone = (m == null) ? null : m.phoneNumber;
      final String toDigits = normalizeToDigits(rawPhone);

      if (toDigits.length() < 10) {
        failed++;
        results.add(MemberSendResult.failed(rawPhone == null ? "" : rawPhone, "Invalid phoneNumber", null, null, null, null));
        continue;
      }

      try {
        Map<String, String> vars = new HashMap<>(globalVars);
        vars.put("firstName", nullToEmpty(m.firstName));
        vars.put("lastName", nullToEmpty(m.lastName));
        if (m.variables != null && !m.variables.isEmpty()) vars.putAll(m.variables);

        String finalText = applyMerge(templateBody, vars);

        if (dryRun) {
          sent++;
          results.add(MemberSendResult.dryRun(toDigits, finalText));
        } else {
          Map<String, Object> waResp = sendTextOrThrow(toDigits, finalText);
          String messageId = extractMessageId(waResp);
          sent++;
          results.add(MemberSendResult.sent(toDigits, messageId, finalText, waResp));
        }

      } catch (WebClientResponseException wex) {
        failed++;
        results.add(MemberSendResult.failed(
            toDigits,
            "WhatsApp API error",
            wex.getStatusCode() == null ? null : wex.getStatusCode().value(),
            safeBody(wex),
            null,
            null
        ));
      } catch (Exception ex) {
        failed++;
        results.add(MemberSendResult.failed(toDigits, ex.getMessage(), null, null, null, null));
      }

      if (!dryRun && throttleMs > 0) {
        try { Thread.sleep(throttleMs); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
      }
    }

    return ResponseEntity.ok(new BulkNotifyResponse(
        "DONE",
        templateKey,
        bulkTraceId,
        now.toString(),
        dryRun,
        throttleMs,
        sent,
        failed,
        results
    ));
  }

  // =========================
  // WhatsApp Cloud API calls
  // =========================

  private String uploadMedia(MultipartFile pdf, String traceId) throws Exception {
    final String url = graphBaseUrl + "/" + phoneNumberId + "/media";

    final ByteArrayResource fileResource = new ByteArrayResource(pdf.getBytes()) {
      @Override public String getFilename() {
        return (pdf.getOriginalFilename() != null && !pdf.getOriginalFilename().isBlank())
            ? pdf.getOriginalFilename()
            : ("invoice_" + traceId + ".pdf");
      }
    };

    MultiValueMap<String, Object> form = new LinkedMultiValueMap<>();
    form.add("messaging_product", "whatsapp");
    form.add("type", "application/pdf");
    form.add("file", fileResource);

    Map<?, ?> resp = webClient.post()
        .uri(url)
        .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
        .contentType(MediaType.MULTIPART_FORM_DATA)
        .bodyValue(form)
        .retrieve()
        .onStatus(HttpStatusCode::isError, r -> r.bodyToMono(String.class).flatMap(b ->
            Mono.error(new WebClientResponseException(
                "Media upload failed. body=" + b,
                r.statusCode().value(),
                r.statusCode().toString(),
                null,
                b.getBytes(),
                null
            ))
        ))
        .bodyToMono(Map.class)
        .block();

    Object id = (resp == null) ? null : resp.get("id");
    if (id == null) throw new IllegalStateException("Media upload succeeded but no id returned: " + resp);
    return id.toString();
  }

  /**
   * ✅ Sends text and throws WebClientResponseException with body on errors
   */
  @SuppressWarnings("unchecked")
  private Map<String, Object> sendTextOrThrow(String toDigits, String text) {
    final String url = graphBaseUrl + "/" + phoneNumberId + "/messages";

    final Map<String, Object> payload = Map.of(
        "messaging_product", "whatsapp",
        "to", toDigits,
        "type", "text",
        "text", Map.of("body", text)
    );

    Map resp = webClient.post()
        .uri(url)
        .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(payload)
        .retrieve()
        .onStatus(HttpStatusCode::isError, r -> r.bodyToMono(String.class).flatMap(b ->
            Mono.error(new WebClientResponseException(
                "Send text failed. body=" + b,
                r.statusCode().value(),
                r.statusCode().toString(),
                null,
                b.getBytes(),
                null
            ))
        ))
        .bodyToMono(Map.class)
        .block();

    return (Map<String, Object>) (resp != null ? resp : Map.of());
  }

  private Map<String, Object> sendDocument(
      String toDigits,
      String mediaId,
      String invoiceId,
      String caption,
      String traceId
  ) {
    final String url = graphBaseUrl + "/" + phoneNumberId + "/messages";

    final Map<String, Object> payload = Map.of(
        "messaging_product", "whatsapp",
        "to", toDigits,
        "type", "document",
        "document", Map.of(
            "id", mediaId,
            "filename", "invoice_" + invoiceId + ".pdf",
            "caption", caption
        )
    );

    Map resp = webClient.post()
        .uri(url)
        .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(payload)
        .retrieve()
        .onStatus(HttpStatusCode::isError, r -> r.bodyToMono(String.class).flatMap(b ->
            Mono.error(new WebClientResponseException(
                "Send document failed. body=" + b,
                r.statusCode().value(),
                r.statusCode().toString(),
                null,
                b.getBytes(),
                null
            ))
        ))
        .bodyToMono(Map.class)
        .block();

    @SuppressWarnings("unchecked")
    Map<String, Object> out = (Map<String, Object>) (resp != null ? resp : Map.of());
    return out;
  }

  // =========================
  // Debug
  // =========================
  @GetMapping("/debug/config")
  public Map<String, Object> debugConfig() {
    return Map.of(
        "graphBaseUrl", graphBaseUrl,
        "phoneNumberId", phoneNumberId,
        "tokenPrefix", accessToken == null ? "null" : accessToken.substring(0, Math.min(12, accessToken.length())) + "...",
        "templatesLoaded", whatsappTemplates == null ? 0 : whatsappTemplates.size(),
        "templateKeys", whatsappTemplates == null ? List.of() : new ArrayList<>(whatsappTemplates.keySet())
    );
  }

  @GetMapping("/debug/phone")
  public Map<String, Object> debugPhone() {
    final String url = graphBaseUrl + "/" + phoneNumberId;
    return webClient.get()
        .uri(url)
        .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
        .retrieve()
        .bodyToMono(Map.class)
        .block();
  }

  // =========================
  // Helpers / Validation
  // =========================

  private static boolean isPdf(MultipartFile f) {
    String ct = (f.getContentType() == null) ? "" : f.getContentType().toLowerCase();
    return ct.contains("application/pdf") || ct.contains("pdf");
  }

  private static String normalizeToDigits(String to) {
    return to == null ? "" : to.replaceAll("[^0-9]", "");
  }

  private static boolean isBlank(String s) {
    return s == null || s.trim().isEmpty();
  }

  private static String nullToEmpty(String s) {
    return s == null ? "" : s.trim();
  }

  private static ResponseStatusException badRequest(String msg) {
    return new ResponseStatusException(HttpStatus.BAD_REQUEST, msg);
  }

  private static String safeBody(WebClientResponseException ex) {
    try { return ex.getResponseBodyAsString(); } catch (Exception ignore) { return "-"; }
  }

  /**
   * Replace placeholders like {firstName}, {lastName}, {clubName}, etc.
   * - Unprovided placeholders are removed.
   */
  private static String applyMerge(String template, Map<String, String> vars) {
    String out = template == null ? "" : template;

    if (vars != null) {
      for (Map.Entry<String, String> e : vars.entrySet()) {
        String k = e.getKey();
        String v = e.getValue() == null ? "" : e.getValue();
        if (k != null) out = out.replace("{" + k + "}", v);
      }
    }

    // remove leftover tokens like {anything}
    out = out.replaceAll("\\{[a-zA-Z0-9_]+\\}", "");
    return out.trim();
  }

  /**
   * Extract message id if present:
   * WhatsApp response often includes:
   * { "messages": [ { "id": "wamid.HBg..." } ] }
   */
  @SuppressWarnings("unchecked")
  private static String extractMessageId(Map<String, Object> waResp) {
    if (waResp == null) return null;
    Object messages = waResp.get("messages");
    if (!(messages instanceof List<?> list) || list.isEmpty()) return null;
    Object first = list.get(0);
    if (!(first instanceof Map<?, ?> m)) return null;
    Object id = ((Map<?, ?>) m).get("id");
    return id == null ? null : String.valueOf(id);
  }

  // =========================
  // Optional: Persist trace (hook into your DDL)
  // =========================
  private void safePersistTrace(
      String toDigits,
      String invoiceId,
      String mediaId,
      String traceId,
      Map<String, Object> waResp,
      Instant createdOn
  ) {
    if (cluboneJdbcTemplate == null) return;
    try {
      // plug your insert here if needed
    } catch (Exception ignore) {}
  }

  private void safePersistFailure(
      String toDigits,
      String invoiceId,
      String traceId,
      Exception ex,
      Instant createdOn
  ) {
    if (cluboneJdbcTemplate == null) return;
    try {
      // plug your failure log here if needed
    } catch (Exception ignore) {}
  }

  // =========================
  // DTOs
  // =========================

  public record SendInvoiceWhatsAppResponse(
      String status,
      String invoiceId,
      String to,
      String mediaId,
      String traceId,
      String sentAtUtc,
      Map<String, Object> whatsappResponse
  ) {}

  public static class NotifySendRequest {
    public String templateName;
    public List<MemberRef> members;

    // global variables for all members (clubName, supportPhone, etc.)
    public Map<String, String> variables;

    // ✅ if true: return merged messages without sending
    public Boolean dryRun;

    // ✅ delay between sends (ms)
    public Long throttleMs;
  }

  public static class MemberRef {
    public String firstName;
    public String lastName;
    public String phoneNumber;

    // per-member variables (expiryDate, visits, etc.)
    public Map<String, String> variables;
  }

  public record BulkNotifyResponse(
      String status,               // DONE
      String templateName,
      String bulkTraceId,
      String executedAtUtc,
      boolean dryRun,
      long throttleMs,
      int sentCount,
      int failedCount,
      List<MemberSendResult> results
  ) {}

  public static class MemberSendResult {
    public String to;
    public String status;           // SENT / FAILED / DRY_RUN
    public String messageId;        // if success
    public String mergedText;       // helpful for debugging
    public Integer httpStatus;      // on failure
    public String errorMessage;     // on failure
    public String errorBody;        // on failure (Graph API body)
    public Map<String, Object> whatsappResponse; // on success

    public static MemberSendResult sent(String to, String messageId, String mergedText, Map<String, Object> waResp) {
      MemberSendResult r = new MemberSendResult();
      r.to = to;
      r.status = "SENT";
      r.messageId = messageId;
      r.mergedText = mergedText;
      r.whatsappResponse = waResp;
      return r;
    }

    public static MemberSendResult dryRun(String to, String mergedText) {
      MemberSendResult r = new MemberSendResult();
      r.to = to;
      r.status = "DRY_RUN";
      r.mergedText = mergedText;
      return r;
    }

    public static MemberSendResult failed(String to, String errorMessage, Integer httpStatus, String errorBody,
                                          String messageId, String mergedText) {
      MemberSendResult r = new MemberSendResult();
      r.to = to;
      r.status = "FAILED";
      r.errorMessage = errorMessage;
      r.httpStatus = httpStatus;
      r.errorBody = errorBody;
      r.messageId = messageId;
      r.mergedText = mergedText;
      return r;
    }
  }
}
