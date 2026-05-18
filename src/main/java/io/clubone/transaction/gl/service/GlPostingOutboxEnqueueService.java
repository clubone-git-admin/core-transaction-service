package io.clubone.transaction.gl.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import io.clubone.transaction.gl.dao.GlPostingOutboxDao;

/**
 * Inserts outbox rows in a new transaction so a failure elsewhere in finalize does not
 * block enqueue (PostgreSQL aborted-transaction semantics).
 */
@Service
public class GlPostingOutboxEnqueueService {

	private final GlPostingOutboxDao outboxDao;

	public GlPostingOutboxEnqueueService(GlPostingOutboxDao outboxDao) {
		this.outboxDao = outboxDao;
	}

	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public boolean enqueueIfAbsent(String eventType, String idempotencyKey, String payloadJson) {
		return outboxDao.enqueueIfAbsent(eventType, idempotencyKey, payloadJson);
	}
}
