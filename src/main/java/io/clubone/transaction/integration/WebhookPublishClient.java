package io.clubone.transaction.integration;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.clubone.transaction.security.TenantContext;
import lombok.extern.slf4j.Slf4j;

@Component
@Slf4j
public class WebhookPublishClient {

  public static final String EVENT_PROSPECT_MEMBERSHIP_PURCHASED = "Prospect.membership.purchased";

  private final RestTemplate restTemplate;
  private final ObjectMapper objectMapper;
  private final JdbcTemplate jdbc;
  private final boolean enabled;
  private final String publishUrl;
  private final UUID defaultApplicationId;
  private final UUID systemActorId;

  public WebhookPublishClient(
      ObjectMapper objectMapper,
      @Qualifier("cluboneJdbcTemplate") JdbcTemplate jdbc,
      @Value("${clubone.integration.webhook.enabled:true}") boolean enabled,
      @Value("${clubone.integration.webhook.publish-url:https://develop-api.clubone.io/integration/api/v1/webhook/events/publish}")
      String publishUrl,
      @Value("${clubone.integration.webhook.default-application-id:}") String defaultApplicationId,
      @Value("${clubone.integration.webhook.system-actor-id:0bed5979-0a9c-4efc-af5b-d1a88cca73f8}") String systemActorId) {
    this.restTemplate = new RestTemplate();
    this.objectMapper = objectMapper;
    this.jdbc = jdbc;
    this.enabled = enabled;
    this.publishUrl = publishUrl;
    this.defaultApplicationId = parseUuid(defaultApplicationId);
    this.systemActorId = parseUuid(systemActorId);
  }

  public void publish(PublishRequest request) {
    if (!enabled || request == null || request.eventType() == null) {
      return;
    }
    try {
      UUID applicationId = request.applicationId() != null
          ? request.applicationId()
          : resolveApplicationId(request.clientRoleId(), request.clientAgreementId());
      UUID locationId = request.locationId() != null
          ? request.locationId()
          : resolveLocationId(request.clientRoleId(), request.clientAgreementId());
      UUID actorId = request.actorId() != null ? request.actorId() : systemActorId;

      if (applicationId == null || locationId == null || actorId == null) {
        log.warn("Webhook publish skipped — missing context eventType={} applicationId={} locationId={} actorId={}",
            request.eventType(), applicationId, locationId, actorId);
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

      HttpEntity<String> entity = new HttpEntity<>(objectMapper.writeValueAsString(body), headers);
      ResponseEntity<String> response = restTemplate.exchange(publishUrl, HttpMethod.POST, entity, String.class);
      log.info("Webhook event queued eventType={} status={}", request.eventType(), response.getStatusCode());
    } catch (Exception ex) {
      log.warn("Webhook publish failed eventType={} error={}", request.eventType(), ex.getMessage());
    }
  }

  private UUID resolveApplicationId(UUID clientRoleId, UUID clientAgreementId) {
    TenantContext ctx = TenantContext.get();
    if (ctx != null) {
      return ctx.applicationId();
    }
    if (clientAgreementId != null) {
      try {
        return jdbc.queryForObject("""
            SELECT a.application_id
            FROM agreements.client_agreement ca
            JOIN agreements.agreement a ON a.agreement_id = ca.agreement_id
            WHERE ca.client_agreement_id = ?
            LIMIT 1
            """, UUID.class, clientAgreementId);
      } catch (Exception ignored) {
      }
    }
    if (clientRoleId != null) {
      try {
        return jdbc.queryForObject("""
            SELECT l.application_id
            FROM clients.client_role cr
            JOIN locations.levels l ON l.level_id = cr.level_id
            WHERE cr.client_role_id = ?
            LIMIT 1
            """, UUID.class, clientRoleId);
      } catch (Exception ignored) {
      }
    }
    return defaultApplicationId;
  }

  private UUID resolveLocationId(UUID clientRoleId, UUID clientAgreementId) {
    TenantContext ctx = TenantContext.get();
    if (ctx != null) {
      return ctx.workingLocation();
    }
    if (clientAgreementId != null) {
      try {
        return jdbc.queryForObject("""
            SELECT ca.level_id
            FROM agreements.client_agreement ca
            WHERE ca.client_agreement_id = ?
            LIMIT 1
            """, UUID.class, clientAgreementId);
      } catch (Exception ignored) {
      }
    }
    if (clientRoleId != null) {
      try {
        return jdbc.queryForObject("""
            SELECT cr.level_id
            FROM clients.client_role cr
            WHERE cr.client_role_id = ?
            LIMIT 1
            """, UUID.class, clientRoleId);
      } catch (Exception ignored) {
      }
    }
    return null;
  }

  private static UUID parseUuid(String value) {
    if (value == null || value.isBlank()) {
      return null;
    }
    try {
      return UUID.fromString(value.trim());
    } catch (IllegalArgumentException ex) {
      return null;
    }
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
