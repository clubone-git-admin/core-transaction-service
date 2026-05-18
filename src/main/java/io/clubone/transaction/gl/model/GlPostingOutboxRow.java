package io.clubone.transaction.gl.model;

import java.time.Instant;
import java.util.UUID;

import lombok.Data;

@Data
public class GlPostingOutboxRow {

	private UUID outboxId;
	private String eventType;
	private String idempotencyKey;
	private String payloadJson;
	private String status;
	private int attemptCount;
	private Instant nextAttemptAt;
	private String lastError;
	private Instant createdOn;
	private Instant processedOn;
}
