package io.clubone.transaction.gl.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.clubone.transaction.gl.config.GlPostingProperties;
import io.clubone.transaction.gl.model.GlPaymentCollectedPayload;
import io.clubone.transaction.gl.model.GlPostingOutboxRow;

@Service
public class GlPostingProcessorService {

	private static final Logger log = LoggerFactory.getLogger(GlPostingProcessorService.class);

	private final GlPostingPostService postService;
	private final GlPostingOutboxStatusService statusService;
	private final GlPostingProperties properties;
	private final ObjectMapper objectMapper;

	public GlPostingProcessorService(GlPostingPostService postService, GlPostingOutboxStatusService statusService,
			GlPostingProperties properties, ObjectMapper objectMapper) {
		this.postService = postService;
		this.statusService = statusService;
		this.properties = properties;
		this.objectMapper = objectMapper;
	}

	public void processOutboxRow(GlPostingOutboxRow row) {
		if (row == null || row.getOutboxId() == null) {
			return;
		}
		try {
			if (!GlPaymentCollectedPayload.EVENT_TYPE.equals(row.getEventType())) {
				throw new IllegalStateException("Unsupported outbox event: " + row.getEventType());
			}
			GlPaymentCollectedPayload payload = objectMapper.readValue(row.getPayloadJson(),
					GlPaymentCollectedPayload.class);
			postService.postPaymentCollected(payload);
			statusService.markDone(row.getOutboxId());
			log.info("[gl-posting] processed outboxId={} cpt={}", row.getOutboxId(),
					payload.getClientPaymentTransactionId());
		} catch (Exception ex) {
			log.warn("[gl-posting] failed outboxId={} attempt={} error={}", row.getOutboxId(), row.getAttemptCount(),
					ex.getMessage(), ex);
			statusService.markFailed(row.getOutboxId(), ex.getMessage(), row.getAttemptCount(),
					properties.getMaxAttempts(), properties.getRetryDelaySeconds());
		}
	}
}
