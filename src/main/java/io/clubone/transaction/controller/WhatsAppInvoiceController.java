package io.clubone.transaction.controller;

import java.time.Instant;
import java.util.Map;
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
 * ✅ SINGLE FILE: Controller + WhatsApp client + validations + (optional) DB logging hook.
 *
 * Endpoint used by Flutter Web:
 *   POST /api/whatsapp/invoice/send
 *   Content-Type: multipart/form-data
 *   Parts:
 *     - pdf: MultipartFile (application/pdf)
 *   Params:
 *     - to: customer whatsapp in +91... or digits
 *     - invoiceId: UUID/string
 *     - caption: optional
 *
 * Required headers:
 *   - X-location-Id
 *   - X-actor-id
 *
 * Tenant context:
 *   - applicationId resolved via TenantContext.get()
 *
 * WhatsApp Cloud API:
 *   1) Upload media:  POST {graphBase}/{phoneNumberId}/media
 *   2) Send message: POST {graphBase}/{phoneNumberId}/messages (type=document, document.id=<media_id>)
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

  @Value("${whatsapp.accessToken:EAAMN5mUdePkBQVgluiGQ2AtWLKwWGuI4Kh6RveSXOxbdseYi17n56dGZBAw2w4s9lpKHFHtzmCR4TqdLLNYeEMEMGxjHqUZAdmBKyHzuU0C21rAyEVp9F72W4gpl3vCj8x6RRaV0WcExlP9gfF5oKHMHrthvRdkoQw2ZAAEPg9rogwsZAjziQiSgIoNy9IYuM2VZColVgHZCUW7FPSZAE8zp0CjtWTBC4L16lS6I4AoUMZBDi4E6volanEmexJtoj9pkerQCZCL2rItEjZCwyDQgZDZD}")
  private String accessToken;

  // Optional: DB logging (wire if you have a table)
  private final JdbcTemplate cluboneJdbcTemplate;

  // WebClient (production-friendly: timeouts / retries can be added here)
  private final WebClient webClient;

  public WhatsAppInvoiceController(
      WebClient.Builder webClientBuilder,
      JdbcTemplate cluboneJdbcTemplate // ✅ if you don't want DB logging, you can pass null from config or remove
  ) {
    this.webClient = webClientBuilder
        .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
        .build();
    this.cluboneJdbcTemplate = cluboneJdbcTemplate;
  }

  // =========================
  // API
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
    // ---- validations
    if (to == null || to.trim().isEmpty()) throw badRequest("'to' is required");
    if (invoiceId == null || invoiceId.trim().isEmpty()) throw badRequest("'invoiceId' is required");
    if (pdf == null || pdf.isEmpty()) throw badRequest("'pdf' is required");
    if (!isPdf(pdf)) throw badRequest("'pdf' must be application/pdf");

    final String toDigits = normalizeToDigits(to);
    if (toDigits.length() < 10) throw badRequest("Invalid WhatsApp number format in 'to'");

    final String finalCaption = (caption == null || caption.trim().isEmpty())
        ? ("Invoice " + invoiceId)
        : caption.trim();
    
    System.out.println("Invoice "+invoiceId);

    // Correlation id (useful for logs)
    final String traceId = UUID.randomUUID().toString();
    final Instant now = Instant.now();

    try {
      // 1) upload pdf to WhatsApp -> mediaId
      final String mediaId = uploadMedia(pdf, traceId);
      
      sendText(toDigits, "Invoice created " + invoiceId);

      // 2) send document message
      final Map<String, Object> waResp = sendDocument(toDigits, mediaId, invoiceId, finalCaption, traceId);

      // 3) optional: persist trace row
      safePersistTrace(toDigits, invoiceId, mediaId, traceId, waResp, now);

      final var resp = new SendInvoiceWhatsAppResponse(
          "SENT",
          invoiceId,
          toDigits,
          mediaId,
          traceId,
          now.toString(),
          waResp
      );

      System.out.println("WA RESP => " + waResp);

      return ResponseEntity.ok(resp);

    } catch (WebClientResponseException wex) {
      // Graph API error response body is valuable
      safePersistFailure(toDigits, invoiceId, traceId, wex, now);

      throw new ResponseStatusException(
          HttpStatus.BAD_GATEWAY,
          "WhatsApp API error: " + wex.getStatusCode() + " body=" + safeBody(wex),
          wex
      );
    } catch (Exception ex) {
      safePersistFailure( toDigits, invoiceId, traceId, ex, now);
      throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Failed to send invoice PDF on WhatsApp", ex);
    }
  }

  // =========================
  // WhatsApp Cloud API calls
  // =========================

  private String uploadMedia(MultipartFile pdf, String traceId) throws Exception {
    final String url = graphBaseUrl + "/" + phoneNumberId + "/media";

    // multipart/form-data: messaging_product + type + file
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
    if (id == null) {
      throw new IllegalStateException("Media upload succeeded but no id returned: " + resp);
    }
    return id.toString();
  }

  private Map<String, Object> sendText(String toDigits, String text) {
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
	      .bodyToMono(Map.class)
	      .block();

	  return (Map<String, Object>) (resp != null ? resp : Map.of());
	}
  
  @GetMapping("/debug/config")
  public Map<String, Object> debugConfig() {
    return Map.of(
        "graphBaseUrl", graphBaseUrl,
        "phoneNumberId", phoneNumberId,
        "tokenPrefix", accessToken == null ? "null" : accessToken.substring(0, Math.min(12, accessToken.length())) + "..."
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
                "Send message failed. body=" + b,
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

  // =========================
  // Helpers / Validation
  // =========================

  private static boolean isPdf(MultipartFile f) {
    // Accept common pdf types
    String ct = (f.getContentType() == null) ? "" : f.getContentType().toLowerCase();
    return ct.contains("application/pdf") || ct.contains("pdf");
  }

  /** WhatsApp expects digits (country code + number). */
  private static String normalizeToDigits(String to) {
    return to == null ? "" : to.replaceAll("[^0-9]", "");
  }

  private static ResponseStatusException badRequest(String msg) {
    return new ResponseStatusException(HttpStatus.BAD_REQUEST, msg);
  }

  private static String safeBody(WebClientResponseException ex) {
    try { return ex.getResponseBodyAsString(); } catch (Exception ignore) { return "-"; }
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

    // ✅ Replace with YOUR table/ddl columns.
    // Best practice: store request, response, status, error, created_on, application_id, location_id, actor_id, invoice_id, to_number, media_id, trace_id
    try {
      // Example (EDIT ME to match your DDL):
      //
      // cluboneJdbcTemplate.update("""
      //   insert into notification.whatsapp_invoice_trace
      //   (trace_id, application_id, location_id, actor_id, invoice_id, to_number, media_id, status, response_json, created_on)
      //   values (?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?)
      // """,
      // traceId, applicationId, locationId, actorId, UUID.fromString(invoiceId), toDigits, mediaId, "SENT",
      // new ObjectMapper().writeValueAsString(waResp),
      // Timestamp.from(createdOn)
      // );
    } catch (Exception ignore) {
      // Never fail primary flow due to logging table
    }
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
      // Similar insert/update for FAILED status.
    } catch (Exception ignore) {}
  }

  // =========================
  // Response DTO (single file)
  // =========================
  public record SendInvoiceWhatsAppResponse(
      String status,          // SENT / FAILED
      String invoiceId,
      String to,
      String mediaId,
      String traceId,
      String sentAtUtc,
      Map<String, Object> whatsappResponse
  ) {}
}



