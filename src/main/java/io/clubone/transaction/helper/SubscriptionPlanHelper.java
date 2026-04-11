package io.clubone.transaction.helper;

import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;
import org.springframework.web.client.RestTemplate;

import io.clubone.transaction.request.BillingQuoteFinalizeSpec;
import io.clubone.transaction.response.BillingQuoteLineItemsResponse;

/**
 * Fetches billing quote line-item details from the billing vendor API using finalize specs.
 * No DB access; callers use the returned payloads in a later step (e.g. subscription creation).
 */
@Service
public class SubscriptionPlanHelper {

	private static final Logger log = LoggerFactory.getLogger(SubscriptionPlanHelper.class);

	@Autowired
	private RestTemplate restTemplate;

	@Value("${billing.quote.line-items.url}")
	private String billingQuoteLineItemsUrl;

	/**
	 * Calls {@code POST .../quote/line-items} once per spec, in order.
	 */
	public List<BillingQuoteLineItemsResponse> fetchQuoteLineItems(List<BillingQuoteFinalizeSpec> specs) {
		if (CollectionUtils.isEmpty(specs)) {
			log.info("[billing-quote/line-items] step=start outcome=skip reason=empty_specs url={}",
					billingQuoteLineItemsUrl);
			return List.of();
		}
		log.info("[billing-quote/line-items] step=start url={} specCount={}", billingQuoteLineItemsUrl, specs.size());
		List<BillingQuoteLineItemsResponse> results = new ArrayList<>(specs.size());
		int index = 0;
		for (BillingQuoteFinalizeSpec spec : specs) {
			index++;
			log.info(
					"[billing-quote/line-items] step=request index={}/{} entityTypeCode={} entityId={} planTemplateId={} startDate={} levelId={}",
					index, specs.size(), spec.getEntityTypeCode(), spec.getEntityId(), spec.getPlanTemplateId(),
					spec.getStartDate(), spec.getLevelId());
			long t0 = System.nanoTime();
			HttpHeaders headers = new HttpHeaders();
			headers.setContentType(MediaType.APPLICATION_JSON);
			HttpEntity<BillingQuoteFinalizeSpec> entity = new HttpEntity<>(spec, headers);
			ResponseEntity<BillingQuoteLineItemsResponse> response = restTemplate.exchange(
					billingQuoteLineItemsUrl,
					HttpMethod.POST,
					entity,
					BillingQuoteLineItemsResponse.class);
			long elapsedMs = (System.nanoTime() - t0) / 1_000_000L;
			if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
				log.error(
						"[billing-quote/line-items] step=response index={} outcome=error httpStatus={} elapsedMs={}",
						index, response.getStatusCode(), elapsedMs);
				throw new IllegalStateException(
						"Billing quote line-items call failed: status=" + response.getStatusCode());
			}
			BillingQuoteLineItemsResponse body = response.getBody();
			results.add(body);
			log.info(
					"[billing-quote/line-items] step=response index={}/{} outcome=ok httpStatus={} elapsedMs={} responsePlanTemplateId={} responseStartDate={} hasLineItems={} hasRecurring={}",
					index, specs.size(), response.getStatusCode(), elapsedMs,
					body != null ? body.getPlanTemplateId() : null,
					body != null ? body.getStartDate() : null,
					body != null && body.getLineItems() != null && body.getLineItems().isArray(),
					body != null && body.getRecurring() != null && body.getRecurring().isArray());
		}
		log.info("[billing-quote/line-items] step=complete outcome=ok totalResponses={}", results.size());
		return results;
	}
}
