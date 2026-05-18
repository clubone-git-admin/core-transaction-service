package io.clubone.transaction.gl.service;

import java.time.Instant;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.clubone.transaction.gl.config.GlPostingProperties;
import io.clubone.transaction.gl.model.GlPaymentCollectedPayload;

@Service
public class GlPostingOutboxService {

	private static final Logger log = LoggerFactory.getLogger(GlPostingOutboxService.class);

	private final GlPostingOutboxEnqueueService outboxEnqueueService;
	private final GlPostingProperties properties;
	private final ObjectMapper objectMapper;

	public GlPostingOutboxService(GlPostingOutboxEnqueueService outboxEnqueueService,
			GlPostingProperties properties, ObjectMapper objectMapper) {
		this.outboxEnqueueService = outboxEnqueueService;
		this.properties = properties;
		this.objectMapper = objectMapper;
	}

	public void enqueuePaymentCollected(GlPaymentCollectedPayload payload) {
		if (!properties.isEnabled()) {
			return;
		}
		if (payload == null || payload.getClientPaymentTransactionId() == null) {
			return;
		}
		if (payload.getAmount() == null || payload.getAmount().signum() <= 0) {
			log.debug("[gl-posting] skip enqueue: non-positive amount cpt={}", payload.getClientPaymentTransactionId());
			return;
		}
		if (payload.getCollectedAt() == null) {
			payload.setCollectedAt(Instant.now());
		}
		// Resolve application in worker; optional default for payload hints only (no DB here).
		if (payload.getApplicationId() == null) {
			payload.setApplicationId(properties.getDefaultApplicationId());
		}

		String idempotencyKey = idempotencyKey(payload.getClientPaymentTransactionId());
		try {
			String json = objectMapper.writeValueAsString(payload);
			boolean inserted = outboxEnqueueService.enqueueIfAbsent(GlPaymentCollectedPayload.EVENT_TYPE,
					idempotencyKey, json);
			if (inserted) {
				log.info("[gl-posting] enqueued cpt={} invoiceId={} amount={}",
						payload.getClientPaymentTransactionId(), payload.getInvoiceId(), payload.getAmount());
			} else {
				log.debug("[gl-posting] duplicate enqueue skipped cpt={}", payload.getClientPaymentTransactionId());
			}
		} catch (JsonProcessingException ex) {
			log.error("[gl-posting] failed to serialize outbox payload cpt={}", payload.getClientPaymentTransactionId(),
					ex);
		}
	}

	public static String idempotencyKey(UUID clientPaymentTransactionId) {
		return "cpt:" + clientPaymentTransactionId;
	}
}
