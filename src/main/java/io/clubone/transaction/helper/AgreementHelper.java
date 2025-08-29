package io.clubone.transaction.helper;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.clubone.transaction.api.vo.MembershipSalesRequestDTO;
import io.clubone.transaction.dao.InvoiceDAO;
import io.clubone.transaction.vo.InvoiceDTO;
import io.clubone.transaction.vo.InvoiceEntityDTO;

@Service
public class AgreementHelper {

	@Autowired
	InvoiceDAO invoiceDAO;

	@Autowired
	private RestTemplate restTemplate;

	@Value("${agreement.purchase.url}")
	private String purchaseAgreementUrl;

	public MembershipSalesRequestDTO createPurchaseAgreementRequest(UUID invoiceId) {
		final InvoiceDTO invoiceDto = invoiceDAO.findResolvedById(invoiceId);
		if (invoiceDto == null) {
			return null; // or throw an IllegalArgumentException
		}

		final MembershipSalesRequestDTO req = new MembershipSalesRequestDTO();

		// Always map these
		req.setClientRoleId(invoiceDto.getClientRoleId());
		req.setLocationId(invoiceDto.getLevelId());

		// Optional: map createdBy -> salesPersonId (drop if not desired)
		req.setSalesPersonId(invoiceDto.getCreatedBy());

		// Defaults
		req.setAddOnMember(false);

		// Find agreement & bundle line items
		InvoiceEntityDTO agreementLine = null;
		InvoiceEntityDTO bundleLine = null;

		final List<InvoiceEntityDTO> lines = invoiceDto.getLineItems();
		if (lines != null) {
			for (InvoiceEntityDTO li : lines) {
				final String desc = li.getEntityDescription();
				if (desc == null)
					continue;

				if (agreementLine == null && "Agreement".equalsIgnoreCase(desc)) {
					agreementLine = li;
				} else if (bundleLine == null && "Bundle".equalsIgnoreCase(desc)) {
					bundleLine = li;
				}

				// Early exit if we already have both
				if (agreementLine != null && bundleLine != null)
					break;
			}
		}

		// Map agreementId and effectiveDate from Agreement line
		if (agreementLine != null) {
			// Per instruction: use entityTypeId as agreementId
			req.setAgreementId(agreementLine.getEntityId());

			if (agreementLine.getContractStartDate() != null) {
				req.setEffectiveDate(agreementLine.getContractStartDate().toString()); // ISO-8601 (yyyy-MM-dd)
			}
		}

		// Map bundleId from Bundle line
		if (bundleLine != null) {
			// Per instruction: use entityTypeId as bundleId
			req.setBundleId(bundleLine.getEntityId());

			// If effectiveDate not set yet, fall back to bundle’s contractStartDate
			// (optional)
			if (req.getEffectiveDate() == null && bundleLine.getContractStartDate() != null) {
				req.setEffectiveDate(bundleLine.getContractStartDate().toString());
			}
		}

		req.setInternalAgreementComment("Purchased from POS");
		req.setAgreementComment("POS Purchase");
		req.setUpsellItems(new ArrayList<>());
		req.setPaymentOptions(new ArrayList<>());
		req.setFundingSources(new ArrayList<>());
		req.setPromotions(new ArrayList<>());

		// Leave these UNSET (null) as requested:
		// paymentOptions, upsellItems, fundingSources, promotions

		ObjectMapper mapper = new ObjectMapper();
		try {
			System.out.println("Data " + mapper.writeValueAsString(req));
		} catch (JsonProcessingException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
		return req;
	}

	public ResponseEntity<String> callMembershipSalesApi(MembershipSalesRequestDTO requestDto) {
		// Headers
		HttpHeaders headers = new HttpHeaders();
		headers.setContentType(MediaType.APPLICATION_JSON);
		

		// Request
		HttpEntity<MembershipSalesRequestDTO> entity = new HttpEntity<>(requestDto, headers);

		// POST call
		return restTemplate.exchange(purchaseAgreementUrl, // API endpoint
				HttpMethod.POST, entity, String.class // Change if API returns a custom response DTO
		);
	}

}
