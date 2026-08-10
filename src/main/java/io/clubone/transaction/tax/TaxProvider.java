package io.clubone.transaction.tax;

import java.time.LocalDate;
import java.util.UUID;

/**
 * Pluggable tax determination provider.
 * INTERNAL is the default; Avalara/Vertex/etc. can implement this later.
 */
public interface TaxProvider {

	String providerCode();

	boolean supports(UUID applicationId);

	TaxDeterminationResult determine(TaxDeterminationRequest request);
}
