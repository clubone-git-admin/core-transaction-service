package io.clubone.transaction.tax;

import java.util.UUID;

/**
 * Extension stub for Avalara / Vertex / custom statutory engines.
 * Enable via finance.tax_provider_config (provider_code=AVALARA, is_enabled=true).
 * Until a real integration is configured, this provider reports unsupported and
 * TaxDeterminationService falls back to INTERNAL.
 */
@org.springframework.stereotype.Component
public class AvalaraTaxProviderStub implements TaxProvider {

	@Override
	public String providerCode() {
		return "AVALARA";
	}

	@Override
	public boolean supports(UUID applicationId) {
		// Real integration would check API credentials in tax_provider_config.config_json.
		return false;
	}

	@Override
	public TaxDeterminationResult determine(TaxDeterminationRequest request) {
		return TaxDeterminationResult.zero(TaxDeterminationResult.Reason.EXTERNAL_PROVIDER, null);
	}
}
