package io.clubone.transaction.gl.model;

import java.util.UUID;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class GlOutboxReplayResponse {

	private boolean replayed;
	private String previousStatus;
	private UUID outboxId;
	private String idempotencyKey;
	private String currentStatus;
	private boolean processedImmediately;
	private String message;
}
