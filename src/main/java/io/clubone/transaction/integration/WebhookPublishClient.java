package io.clubone.transaction.integration;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadPoolExecutor;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.clubone.transaction.security.TenantContext;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;

/**
 * Domain → integration webhook publisher.
 * <ul>
 *   <li>Never throws to callers (business flow must not break).</li>
 *   <li>HTTP runs asynchronously after DB commit.</li>
 *   <li>{@code application-id} and {@code X-Actor-Id} come from the parent request
 *       (TenantContext / inbound headers) — never from hardcoded config.</li>
 * </ul>
 */
@Component
@Slf4j
public class WebhookPublishClient {

  public static final String EVENT_PROSPECT_MEMBERSHIP_PURCHASED = "Prospect.membership.purchased";

  private final RestTemplate restTemplate;
  private final ObjectMapper objectMapper;
  private final JdbcTemplate jdbc;
  private final boolean enabled;
  private final String publishUrl;
  private final ThreadPoolTaskExecutor asyncExecutor;

  public WebhookPublishClient(
      ObjectMapper objectMapper,
      @Qualifier("cluboneJdbcTemplate") JdbcTemplate jdbc,
      @Value("${clubone.integration.webhook.enabled:true}") boolean enabled,
      @Value("${clubone.integration.webhook.publish-url:https://develop-api.clubone.io/integration/api/v1/webhook/events/publish}")
      String publishUrl,
      @Value("${clubone.integration.webhook.connect-timeout-ms:2000}") int connectTimeoutMs,
      @Value("${clubone.integration.webhook.read-timeout-ms:5000}") int readTimeoutMs,
      @Value("${clubone.integration.webhook.async-pool-size:4}") int asyncPoolSize) {
    this.objectMapper = objectMapper;
    this.jdbc = jdbc;
    this.enabled = enabled;
    this.publishUrl = publishUrl;
    this.restTemplate = buildRestTemplate(connectTimeoutMs, readTimeoutMs);
    this.asyncExecutor = buildAsyncExecutor(asyncPoolSize);
  }

  @PostConstruct
  void logWebhookTarget() {
    log.info("WebhookPublishClient enabled={} publishUrl={} afterCommit=true async=true headerContext=true",
        enabled, publishUrl);
  }

  @PreDestroy
  void shutdown() {
    asyncExecutor.shutdown();
  }

  /**
   * Schedules webhook publish after the surrounding transaction commits (or immediately if none).
   * Never throws; HTTP is always async so the domain API is not blocked by integration latency.
   */
  public void publish(PublishRequest request) {
    try {
      if (!enabled || request == null || request.eventType() == null) {
        log.warn("Webhook publish skipped — client disabled or empty request enabled={} eventType={}",
            enabled, request != null ? request.eventType() : null);
        return;
      }
      // Capture parent request headers / TenantContext on the calling thread before async.
      PublishRequest snapshot = snapshotFromParentRequest(request);
      if (TransactionSynchronizationManager.isSynchronizationActive()) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
          @Override
          public void afterCommit() {
            dispatchAsync(snapshot);
          }
        });
        log.info("Webhook publish deferred until afterCommit (async) eventType={} idempotencyKey={}",
            request.eventType(), request.idempotencyKey());
        return;
      }
      dispatchAsync(snapshot);
    } catch (Exception ex) {
      log.warn("Webhook publish schedule failed eventType={} error={}",
          request != null ? request.eventType() : null, ex.getMessage());
    }
  }

  private void dispatchAsync(PublishRequest request) {
    try {
      asyncExecutor.execute(() -> {
        try {
          publishNow(request);
        } catch (Throwable t) {
          log.warn("Webhook publish async failed eventType={} error={}",
              request.eventType(), t.getMessage(), t);
        }
      });
    } catch (Exception ex) {
      log.warn("Webhook publish enqueue failed eventType={} error={}",
          request.eventType(), ex.getMessage());
    }
  }

  /**
   * Prefer parent inbound headers (via TenantContext), then raw servlet headers.
   * Explicit request values are last resort only when headers were not present.
   */
  private PublishRequest snapshotFromParentRequest(PublishRequest request) {
    UUID applicationId = resolveParentApplicationId(request.applicationId());
    UUID actorId = resolveParentActorId(request.actorId());
    UUID locationId = resolveParentLocationId(request.locationId());
    return new PublishRequest(
        request.eventType(),
        request.payload(),
        applicationId,
        locationId,
        actorId,
        request.clientRoleId(),
        request.clientAgreementId(),
        request.sourceEntityId(),
        request.idempotencyKey(),
        request.correlationId(),
        request.sourceService());
  }

  private UUID resolveParentApplicationId(UUID explicit) {
    TenantContext ctx = TenantContext.get();
    if (ctx != null && ctx.applicationId() != null) {
      return ctx.applicationId();
    }
    UUID fromHeader = headerUuid("application-id", "X-Application-Id");
    if (fromHeader != null) {
      return fromHeader;
    }
    return explicit;
  }

  private UUID resolveParentActorId(UUID explicit) {
    TenantContext ctx = TenantContext.get();
    if (ctx != null && ctx.applicationUserId() != null) {
      return ctx.applicationUserId();
    }
    UUID fromHeader = headerUuid("X-Actor-Id");
    if (fromHeader != null) {
      return fromHeader;
    }
    return explicit;
  }

  private UUID resolveParentLocationId(UUID explicit) {
    if (explicit != null) {
      return explicit;
    }
    TenantContext ctx = TenantContext.get();
    if (ctx != null && ctx.workingLocation() != null) {
      return ctx.workingLocation();
    }
    return headerUuid("X-Location-Id");
  }

  private void publishNow(PublishRequest request) {
    try {
      UUID applicationId = request.applicationId();
      UUID actorId = request.actorId();
      UUID locationId = request.locationId() != null
          ? request.locationId()
          : resolveLocationId(request.clientRoleId(), request.clientAgreementId());

      if (applicationId == null || actorId == null) {
        log.warn(
            "Webhook publish skipped — missing parent application-id / X-Actor-Id headers "
                + "eventType={} applicationId={} actorId={}",
            request.eventType(), applicationId, actorId);
        return;
      }
      if (locationId == null) {
        log.warn("Webhook publish skipped — missing locationId eventType={}", request.eventType());
        return;
      }

      Map<String, Object> body = new LinkedHashMap<>();
      body.put("eventType", request.eventType());
      body.put("payload", request.payload() != null ? request.payload() : Map.of());
      if (request.idempotencyKey() != null) {
        body.put("idempotencyKey", request.idempotencyKey());
      }
      if (request.correlationId() != null) {
        body.put("correlationId", request.correlationId());
      }
      body.put("sourceService", request.sourceService() != null ? request.sourceService() : "transaction-service");
      if (request.sourceEntityId() != null) {
        body.put("sourceEntityId", request.sourceEntityId().toString());
      }
      body.put("locationId", locationId.toString());

      HttpHeaders headers = new HttpHeaders();
      headers.setContentType(MediaType.APPLICATION_JSON);
      headers.set("X-Actor-Id", actorId.toString());
      headers.set("X-Location-Id", locationId.toString());
      headers.set("application-id", applicationId.toString());

      log.info("Webhook publish POST (async) url={} eventType={} applicationId={} locationId={} actorId={} idempotencyKey={}",
          publishUrl, request.eventType(), applicationId, locationId, actorId, request.idempotencyKey());

      HttpEntity<String> entity = new HttpEntity<>(objectMapper.writeValueAsString(body), headers);
      ResponseEntity<String> response = restTemplate.exchange(publishUrl, HttpMethod.POST, entity, String.class);
      String integrationStatus = parseIntegrationStatus(response.getBody());
      if ("SKIPPED".equalsIgnoreCase(integrationStatus)) {
        log.warn(
            "Webhook publish accepted but SKIPPED by integration-service eventType={} applicationId={} — "
                + "enable integration, configure an external endpoint, and subscribe to this event "
                + "(rows land in webhook.wh_event_outbox, not transaction DB)",
            request.eventType(), applicationId);
      } else {
        log.info("Webhook event published eventType={} applicationId={} http={} integrationStatus={} body={}",
            request.eventType(), applicationId, response.getStatusCode(), integrationStatus, response.getBody());
      }
    } catch (Exception ex) {
      log.warn("Webhook publish failed eventType={} url={} error={}", request.eventType(), publishUrl, ex.getMessage(),
          ex);
    }
  }

  private UUID resolveLocationId(UUID clientRoleId, UUID clientAgreementId) {
    if (clientAgreementId != null) {
      try {
        return jdbc.queryForObject("""
            SELECT COALESCE(
              lv_purch.reference_entity_id,
              lv_agreement.reference_entity_id,
              cr.location_id
            )
            FROM client_agreements.client_agreement ca
            JOIN clients.client_role cr ON cr.client_role_id = ca.client_role_id
            LEFT JOIN locations.levels lv_purch
              ON lv_purch.level_id = ca.purchased_level_id
            LEFT JOIN agreements.agreement_location al
              ON al.agreement_location_id = ca.agreement_location_id
            LEFT JOIN locations.levels lv_agreement
              ON lv_agreement.level_id = al.level_id
            WHERE ca.client_agreement_id = ?
              AND COALESCE(ca.is_active, TRUE) = TRUE
            LIMIT 1
            """, UUID.class, clientAgreementId);
      } catch (Exception ex) {
        log.warn("Webhook location resolve failed for clientAgreementId={}: {}",
            clientAgreementId, ex.getMessage());
      }
    }
    if (clientRoleId != null) {
      try {
        return jdbc.queryForObject("""
            SELECT cr.location_id
            FROM clients.client_role cr
            WHERE cr.client_role_id = ?
              AND COALESCE(cr.is_active, TRUE) = TRUE
            LIMIT 1
            """, UUID.class, clientRoleId);
      } catch (Exception ex) {
        log.warn("Webhook location resolve failed for clientRoleId={}: {}",
            clientRoleId, ex.getMessage());
      }
    }
    return null;
  }

  private String parseIntegrationStatus(String body) {
    if (body == null || body.isBlank()) {
      return "UNKNOWN";
    }
    try {
      JsonNode root = objectMapper.readTree(body);
      JsonNode status = root.path("data").path("status");
      return status.isMissingNode() || status.isNull() ? "UNKNOWN" : status.asText("UNKNOWN");
    } catch (Exception ex) {
      return "UNKNOWN";
    }
  }

  private static UUID headerUuid(String... names) {
    RequestAttributes attrs = RequestContextHolder.getRequestAttributes();
    if (!(attrs instanceof ServletRequestAttributes servletAttrs)) {
      return null;
    }
    HttpServletRequest req = servletAttrs.getRequest();
    for (String name : names) {
      String value = req.getHeader(name);
      if (value == null || value.isBlank()) {
        continue;
      }
      try {
        return UUID.fromString(value.trim());
      } catch (IllegalArgumentException ignored) {
      }
    }
    return null;
  }

  private static RestTemplate buildRestTemplate(int connectTimeoutMs, int readTimeoutMs) {
    SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
    factory.setConnectTimeout(Math.max(500, connectTimeoutMs));
    factory.setReadTimeout(Math.max(1000, readTimeoutMs));
    return new RestTemplate(factory);
  }

  private static ThreadPoolTaskExecutor buildAsyncExecutor(int poolSize) {
    int size = Math.max(2, poolSize);
    ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
    executor.setThreadNamePrefix("webhook-publish-");
    executor.setCorePoolSize(size);
    executor.setMaxPoolSize(size);
    executor.setQueueCapacity(500);
    executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
    executor.initialize();
    return executor;
  }

  public record PublishRequest(
      String eventType,
      Map<String, Object> payload,
      UUID applicationId,
      UUID locationId,
      UUID actorId,
      UUID clientRoleId,
      UUID clientAgreementId,
      UUID sourceEntityId,
      String idempotencyKey,
      String correlationId,
      String sourceService) {
  }
}
