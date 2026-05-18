package io.clubone.transaction.gl.model;

import java.util.UUID;

import lombok.Data;

@Data
public class GlMappingRuleRow {

	private UUID glMappingRuleId;
	private UUID debitGlCodeId;
	private UUID creditGlCodeId;
	private UUID journalTypeId;
	private UUID lineOfBusinessId;
	private boolean autoPost;
}
