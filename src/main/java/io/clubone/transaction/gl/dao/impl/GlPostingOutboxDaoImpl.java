package io.clubone.transaction.gl.dao.impl;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import io.clubone.transaction.gl.dao.GlPostingOutboxDao;
import io.clubone.transaction.gl.model.GlPostingOutboxRow;

@Repository
public class GlPostingOutboxDaoImpl implements GlPostingOutboxDao {

	private final JdbcTemplate jdbc;

	public GlPostingOutboxDaoImpl(@Qualifier("cluboneJdbcTemplate") JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	private static final RowMapper<GlPostingOutboxRow> ROW_MAPPER = new RowMapper<>() {
		@Override
		public GlPostingOutboxRow mapRow(ResultSet rs, int rowNum) throws SQLException {
			GlPostingOutboxRow row = new GlPostingOutboxRow();
			row.setOutboxId(rs.getObject("outbox_id", UUID.class));
			row.setEventType(rs.getString("event_type"));
			row.setIdempotencyKey(rs.getString("idempotency_key"));
			row.setPayloadJson(rs.getString("payload_json"));
			row.setStatus(rs.getString("status"));
			row.setAttemptCount(rs.getInt("attempt_count"));
			Timestamp next = rs.getTimestamp("next_attempt_at");
			row.setNextAttemptAt(next == null ? null : next.toInstant());
			row.setLastError(rs.getString("last_error"));
			Timestamp created = rs.getTimestamp("created_on");
			row.setCreatedOn(created == null ? null : created.toInstant());
			Timestamp processed = rs.getTimestamp("processed_on");
			row.setProcessedOn(processed == null ? null : processed.toInstant());
			return row;
		}
	};

	@Override
	public boolean enqueueIfAbsent(String eventType, String idempotencyKey, String payloadJson) {
		try {
			int inserted = jdbc.update("""
					INSERT INTO reconciliation.gl_posting_outbox (
					    event_type, idempotency_key, payload_json, status, next_attempt_at
					) VALUES (?, ?, ?::jsonb, 'PENDING', now())
					""", eventType, idempotencyKey, payloadJson);
			return inserted > 0;
		} catch (DuplicateKeyException ex) {
			return false;
		}
	}

	@Override
	public Optional<GlPostingOutboxRow> findByIdempotencyKey(String idempotencyKey) {
		List<GlPostingOutboxRow> rows = jdbc.query("""
				SELECT outbox_id, event_type, idempotency_key, payload_json::text AS payload_json,
				       status, attempt_count, next_attempt_at, last_error, created_on, processed_on
				FROM reconciliation.gl_posting_outbox
				WHERE idempotency_key = ?
				""", ROW_MAPPER, idempotencyKey);
		return rows.stream().findFirst();
	}

	@Override
	public List<GlPostingOutboxRow> claimPendingBatch(int batchSize) {
		return jdbc.query("""
				WITH picked AS (
				    SELECT outbox_id
				    FROM reconciliation.gl_posting_outbox
				    WHERE status IN ('PENDING', 'PROCESSING')
				      AND next_attempt_at <= now()
				    ORDER BY created_on
				    LIMIT ?
				    FOR UPDATE SKIP LOCKED
				)
				UPDATE reconciliation.gl_posting_outbox o
				SET status = 'PROCESSING',
				    attempt_count = o.attempt_count + 1,
				    next_attempt_at = now() + interval '10 minutes'
				FROM picked
				WHERE o.outbox_id = picked.outbox_id
				RETURNING o.outbox_id, o.event_type, o.idempotency_key, o.payload_json::text AS payload_json,
				          o.status, o.attempt_count, o.next_attempt_at, o.last_error, o.created_on, o.processed_on
				""", ROW_MAPPER, batchSize);
	}

	@Override
	public void markDone(UUID outboxId) {
		jdbc.update("""
				UPDATE reconciliation.gl_posting_outbox
				SET status = 'DONE', processed_on = now(), last_error = NULL
				WHERE outbox_id = ?
				""", outboxId);
	}

	@Override
	public void markFailed(UUID outboxId, String error, int attemptCount, int maxAttempts, long retryDelaySeconds) {
		if (attemptCount >= maxAttempts) {
			jdbc.update("""
					UPDATE reconciliation.gl_posting_outbox
					SET status = 'FAILED', last_error = ?, processed_on = now()
					WHERE outbox_id = ?
					""", truncate(error), outboxId);
		} else {
			releaseToPending(outboxId, error, attemptCount, maxAttempts, retryDelaySeconds);
		}
	}

	@Override
	public void releaseToPending(UUID outboxId, String error, int attemptCount, int maxAttempts,
			long retryDelaySeconds) {
		jdbc.update("""
				UPDATE reconciliation.gl_posting_outbox
				SET status = 'PENDING',
				    last_error = ?,
				    next_attempt_at = now() + (?::bigint * interval '1 second')
				WHERE outbox_id = ?
				""", truncate(error), retryDelaySeconds, outboxId);
	}

	@Override
	public int resetFailedForReplay(String idempotencyKey) {
		return jdbc.update("""
				UPDATE reconciliation.gl_posting_outbox
				SET status = 'PENDING',
				    attempt_count = 0,
				    last_error = NULL,
				    processed_on = NULL,
				    next_attempt_at = now()
				WHERE idempotency_key = ?
				  AND status = 'FAILED'
				""", idempotencyKey);
	}

	private static String truncate(String error) {
		if (error == null) {
			return null;
		}
		return error.length() <= 4000 ? error : error.substring(0, 4000);
	}
}
