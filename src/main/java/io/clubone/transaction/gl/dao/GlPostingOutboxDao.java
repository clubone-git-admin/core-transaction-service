package io.clubone.transaction.gl.dao;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import io.clubone.transaction.gl.model.GlPostingOutboxRow;

public interface GlPostingOutboxDao {

	boolean enqueueIfAbsent(String eventType, String idempotencyKey, String payloadJson);

	Optional<GlPostingOutboxRow> findByIdempotencyKey(String idempotencyKey);

	List<GlPostingOutboxRow> claimPendingBatch(int batchSize);

	void markDone(UUID outboxId);

	void markFailed(UUID outboxId, String error, int attemptCount, int maxAttempts, long retryDelaySeconds);

	void releaseToPending(UUID outboxId, String error, int attemptCount, int maxAttempts, long retryDelaySeconds);

	/**
	 * Resets a {@code FAILED} row to {@code PENDING} for manual replay. Returns rows updated (0 or 1).
	 */
	int resetFailedForReplay(String idempotencyKey);
}
