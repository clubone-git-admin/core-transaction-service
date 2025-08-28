package io.clubone.transaction.service.impl;

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

import io.clubone.transaction.response.PaymentResponseDTO;
import io.clubone.transaction.service.PaymentService;
import io.clubone.transaction.vo.PaymentRequestDTO;

@Service
public class PaymentServiceImpl implements PaymentService {

	@Autowired
	private RestTemplate restTemplate;

	@Value("${payment.api.url}")
	private String paymentApiUrl;

	@Override
	public UUID processManualPayment(PaymentRequestDTO request) {
		HttpHeaders headers = new HttpHeaders();
		headers.setContentType(MediaType.APPLICATION_JSON);

		HttpEntity<PaymentRequestDTO> entity = new HttpEntity<>(request, headers);
		System.out.println("clientRoleId "+request.getClientRoleId());

		ResponseEntity<PaymentResponseDTO> response = restTemplate.exchange(paymentApiUrl, HttpMethod.POST, entity,
				PaymentResponseDTO.class);

		if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
			return response.getBody().getTransactionId();
		} else {
			throw new RuntimeException("Failed to create payment. Status: " + response.getStatusCode());
		}
	}

}
