package io.clubone.transaction.gl.model;

import lombok.Data;

@Data
public class GlOutboxReplayRequest {

	/** Outbox idempotency key, e.g. {@code cpt:{clientPaymentTransactionId}}. */
	private String idempotencyKey;

	/** When true, process immediately instead of waiting for the scheduler. */
	private boolean processImmediately;
}
