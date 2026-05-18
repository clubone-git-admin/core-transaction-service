package io.clubone.transaction.gl.service;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;

import io.clubone.transaction.gl.config.GlPostingProperties;
import io.clubone.transaction.gl.dao.GlPostingOutboxDao;
import io.clubone.transaction.gl.model.GlOutboxReplayResponse;
import io.clubone.transaction.gl.model.GlOutboxStatusResponse;
import io.clubone.transaction.gl.model.GlPostingOutboxRow;

@Service
public class GlPostingAdminService {

	private final GlPostingOutboxDao outboxDao;
	private final GlPostingProcessorService processorService;
	private final GlPostingProperties properties;

	public GlPostingAdminService(GlPostingOutboxDao outboxDao, GlPostingProcessorService processorService,
			GlPostingProperties properties) {
		this.outboxDao = outboxDao;
		this.processorService = processorService;
		this.properties = properties;
	}

	public GlOutboxStatusResponse getOutboxStatus(String idempotencyKey) {
		String key = requireIdempotencyKey(idempotencyKey);
		GlPostingOutboxRow row = outboxDao.findByIdempotencyKey(key)
				.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
						"Outbox row not found for idempotencyKey=" + key));
		return toStatusResponse(row);
	}

	public GlOutboxReplayResponse replayFailed(String idempotencyKey, boolean processImmediately) {
		if (!properties.isEnabled()) {
			throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "GL posting is disabled");
		}
		String key = requireIdempotencyKey(idempotencyKey);
		GlPostingOutboxRow before = outboxDao.findByIdempotencyKey(key)
				.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
						"Outbox row not found for idempotencyKey=" + key));

		if (!"FAILED".equalsIgnoreCase(before.getStatus())) {
			throw new ResponseStatusException(HttpStatus.CONFLICT,
					"Only FAILED outbox rows can be replayed; current status=" + before.getStatus());
		}

		int updated = outboxDao.resetFailedForReplay(key);
		if (updated == 0) {
			throw new ResponseStatusException(HttpStatus.CONFLICT,
					"Outbox row could not be reset (status may have changed)");
		}

		GlPostingOutboxRow afterReset = outboxDao.findByIdempotencyKey(key).orElse(before);
		boolean processed = false;
		String message = "Queued for scheduler";

		if (processImmediately) {
			GlPostingOutboxRow toProcess = afterReset;
			toProcess.setStatus("PROCESSING");
			toProcess.setAttemptCount(afterReset.getAttemptCount() + 1);
			processorService.processOutboxRow(toProcess);
			processed = true;
			message = "Processed immediately";
			afterReset = outboxDao.findByIdempotencyKey(key).orElse(afterReset);
		}

		return GlOutboxReplayResponse.builder()
				.replayed(true)
				.previousStatus(before.getStatus())
				.outboxId(afterReset.getOutboxId())
				.idempotencyKey(key)
				.currentStatus(afterReset.getStatus())
				.processedImmediately(processed)
				.message(message)
				.build();
	}

	private static String requireIdempotencyKey(String idempotencyKey) {
		if (!StringUtils.hasText(idempotencyKey)) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "idempotencyKey is required");
		}
		return idempotencyKey.trim();
	}

	private static GlOutboxStatusResponse toStatusResponse(GlPostingOutboxRow row) {
		return GlOutboxStatusResponse.builder()
				.outboxId(row.getOutboxId())
				.idempotencyKey(row.getIdempotencyKey())
				.eventType(row.getEventType())
				.status(row.getStatus())
				.attemptCount(row.getAttemptCount())
				.nextAttemptAt(row.getNextAttemptAt())
				.lastError(row.getLastError())
				.createdOn(row.getCreatedOn())
				.processedOn(row.getProcessedOn())
				.build();
	}
}
