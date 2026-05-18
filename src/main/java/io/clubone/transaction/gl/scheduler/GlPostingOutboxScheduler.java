package io.clubone.transaction.gl.scheduler;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import io.clubone.transaction.gl.config.GlPostingProperties;
import io.clubone.transaction.gl.dao.GlPostingOutboxDao;
import io.clubone.transaction.gl.model.GlPostingOutboxRow;
import io.clubone.transaction.gl.service.GlPostingProcessorService;

@Component
public class GlPostingOutboxScheduler {

	private static final Logger log = LoggerFactory.getLogger(GlPostingOutboxScheduler.class);

	private final GlPostingProperties properties;
	private final GlPostingOutboxDao outboxDao;
	private final GlPostingProcessorService processorService;

	public GlPostingOutboxScheduler(GlPostingProperties properties, GlPostingOutboxDao outboxDao,
			GlPostingProcessorService processorService) {
		this.properties = properties;
		this.outboxDao = outboxDao;
		this.processorService = processorService;
	}

	@Scheduled(fixedDelayString = "${clubone.gl.posting.poll-interval-ms:30000}")
	public void pollOutbox() {
		if (!properties.isEnabled()) {
			return;
		}
		List<GlPostingOutboxRow> batch = outboxDao.claimPendingBatch(properties.getBatchSize());
		if (batch.isEmpty()) {
			return;
		}
		log.debug("[gl-posting] processing batch size={}", batch.size());
		for (GlPostingOutboxRow row : batch) {
			processorService.processOutboxRow(row);
		}
	}
}
