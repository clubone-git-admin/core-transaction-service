package io.clubone.transaction.gl.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
@ConfigurationProperties(prefix = "clubone.gl.posting")
public class GlPostingProperties {

	private boolean enabled = true;
	private long pollIntervalMs = 30_000L;
	private int batchSize = 20;
	private int maxAttempts = 10;
	private long retryDelaySeconds = 60L;
	private UUID defaultApplicationId;
	private String sourceTypeCode = "PAYMENT";
	private String transactionTypeCode = "PAYMENT_COLLECTION";
	private String journalTypeCode = "CASH_RECEIPT";
	private String entityTypeCode = "PAYMENT_TRANSACTION";

	public boolean isEnabled() {
		return enabled;
	}

	public void setEnabled(boolean enabled) {
		this.enabled = enabled;
	}

	public long getPollIntervalMs() {
		return pollIntervalMs;
	}

	public void setPollIntervalMs(long pollIntervalMs) {
		this.pollIntervalMs = pollIntervalMs;
	}

	public int getBatchSize() {
		return batchSize;
	}

	public void setBatchSize(int batchSize) {
		this.batchSize = batchSize;
	}

	public int getMaxAttempts() {
		return maxAttempts;
	}

	public void setMaxAttempts(int maxAttempts) {
		this.maxAttempts = maxAttempts;
	}

	public long getRetryDelaySeconds() {
		return retryDelaySeconds;
	}

	public void setRetryDelaySeconds(long retryDelaySeconds) {
		this.retryDelaySeconds = retryDelaySeconds;
	}

	public UUID getDefaultApplicationId() {
		return defaultApplicationId;
	}

	public void setDefaultApplicationId(UUID defaultApplicationId) {
		this.defaultApplicationId = defaultApplicationId;
	}

	public String getSourceTypeCode() {
		return sourceTypeCode;
	}

	public void setSourceTypeCode(String sourceTypeCode) {
		this.sourceTypeCode = sourceTypeCode;
	}

	public String getTransactionTypeCode() {
		return transactionTypeCode;
	}

	public void setTransactionTypeCode(String transactionTypeCode) {
		this.transactionTypeCode = transactionTypeCode;
	}

	public String getJournalTypeCode() {
		return journalTypeCode;
	}

	public void setJournalTypeCode(String journalTypeCode) {
		this.journalTypeCode = journalTypeCode;
	}

	public String getEntityTypeCode() {
		return entityTypeCode;
	}

	public void setEntityTypeCode(String entityTypeCode) {
		this.entityTypeCode = entityTypeCode;
	}
}
