package io.clubone.transaction.integration;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import lombok.extern.slf4j.Slf4j;

@Component
@Slf4j
public class WebhookMembershipPurchasePublisher {

  private final WebhookPublishClient Webhook;
  private final JdbcTemplate jdbc;

  public WebhookMembershipPurchasePublisher(
      WebhookPublishClient Webhook,
      @Qualifier("cluboneJdbcTemplate") JdbcTemplate jdbc) {
    this.Webhook = Webhook;
    this.jdbc = jdbc;
  }

  public void publishAfterPaymentSuccess(
      UUID invoiceId,
      UUID transactionId,
      UUID clientPaymentTransactionId,
      UUID clientRoleId,
      UUID clientAgreementId,
      UUID levelId,
      BigDecimal amount,
      UUID actorId) {

    if (clientAgreementId == null) {
      log.warn(
          "Webhook membership purchase NOT published — clientAgreementId is null invoiceId={} transactionId={}",
          invoiceId, transactionId);
      return;
    }

    log.info(
        "Webhook membership purchase publish start invoiceId={} transactionId={} clientAgreementId={} clientRoleId={} amount={}",
        invoiceId, transactionId, clientAgreementId, clientRoleId, amount);

    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("clientRoleId", clientRoleId);
    payload.put("clientAgreementId", clientAgreementId);
    payload.put("invoiceId", invoiceId);
    payload.put("transactionId", transactionId);
    payload.put("clientPaymentTransactionId", clientPaymentTransactionId);
    payload.put("amount", amount);
    payload.put("agreementId", lookupCatalogAgreementId(clientAgreementId));

    Webhook.publish(new WebhookPublishClient.PublishRequest(
        WebhookPublishClient.EVENT_PROSPECT_MEMBERSHIP_PURCHASED,
        payload,
        null,
        levelId,
        actorId,
        clientRoleId,
        clientAgreementId,
        clientAgreementId,
        "purchase-paid-" + invoiceId,
        invoiceId != null ? invoiceId.toString() : null,
        "transaction-service"));
  }

  private UUID lookupCatalogAgreementId(UUID clientAgreementId) {
    try {
      return jdbc.queryForObject("""
          SELECT agreement_id
          FROM client_agreements.client_agreement
          WHERE client_agreement_id = ?
          LIMIT 1
          """, UUID.class, clientAgreementId);
    } catch (Exception ex) {
      return null;
    }
  }
}
