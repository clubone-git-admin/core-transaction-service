package io.clubone.transaction.gl.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import io.clubone.transaction.gl.model.GlOutboxReplayRequest;
import io.clubone.transaction.gl.model.GlOutboxReplayResponse;
import io.clubone.transaction.gl.model.GlOutboxStatusResponse;
import io.clubone.transaction.gl.service.GlPostingAdminService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/admin/gl-posting/outbox")
@RequiredArgsConstructor
@Tag(name = "GL Posting Admin", description = "Replay failed GL posting outbox events")
public class GlPostingAdminController {

	private final GlPostingAdminService adminService;

	@GetMapping
	@Operation(summary = "Get outbox row status by idempotency key")
	public ResponseEntity<GlOutboxStatusResponse> getStatus(
			@RequestParam("idempotencyKey") String idempotencyKey) {
		return ResponseEntity.ok(adminService.getOutboxStatus(idempotencyKey));
	}

	@PostMapping("/replay")
	@Operation(summary = "Replay a FAILED outbox row (resets to PENDING; optional immediate processing)")
	public ResponseEntity<GlOutboxReplayResponse> replayFailed(@RequestBody GlOutboxReplayRequest request) {
		boolean processNow = request != null && request.isProcessImmediately();
		String key = request != null ? request.getIdempotencyKey() : null;
		return ResponseEntity.ok(adminService.replayFailed(key, processNow));
	}
}
