package io.clubone.transaction.gl.service;

import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import io.clubone.transaction.gl.dao.GlPostingOutboxDao;

/**
 * Outbox status updates in a new transaction so a failed GL post does not leave the
 * worker transaction aborted (PostgreSQL 25P02).
 */
@Service
public class GlPostingOutboxStatusService {

	private final GlPostingOutboxDao outboxDao;

	public GlPostingOutboxStatusService(GlPostingOutboxDao outboxDao) {
		this.outboxDao = outboxDao;
	}

	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public void markDone(UUID outboxId) {
		outboxDao.markDone(outboxId);
	}

	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public void markFailed(UUID outboxId, String error, int attemptCount, int maxAttempts, long retryDelaySeconds) {
		outboxDao.markFailed(outboxId, error, attemptCount, maxAttempts, retryDelaySeconds);
	}
}
