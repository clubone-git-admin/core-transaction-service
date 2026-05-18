package io.clubone.transaction.gl.model;

import java.time.Instant;
import java.util.UUID;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class GlOutboxStatusResponse {

	private UUID outboxId;
	private String idempotencyKey;
	private String eventType;
	private String status;
	private int attemptCount;
	private Instant nextAttemptAt;
	private String lastError;
	private Instant createdOn;
	private Instant processedOn;
}
