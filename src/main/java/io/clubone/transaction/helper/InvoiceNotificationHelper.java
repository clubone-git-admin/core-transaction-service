package io.clubone.transaction.helper;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import io.clubone.transaction.v2.vo.NotificationRequestDTO;

@Service
public class InvoiceNotificationHelper {

    @Autowired
    private RestTemplate restTemplate;

    // -----------------------------------
    // URLs (configure in application.yml)
    // -----------------------------------

    @Value("${notification.payload.url}")
    private String invoiceEmailPayloadUrl;
    // example:
    // http://localhost:8014/notification/invoice/{invoiceId}/email-payload

    @Value("${notification.send.url}")
    private String notificationSendUrl;
    // example:
    // http://localhost:8014/notification/send

    /**
     * Step-1:
     * Call InvoiceNotificationPayloadController to build payload
     */
    public NotificationRequestDTO buildInvoiceEmailPayload(
            UUID invoiceId,
            String paymentMethodType,   // CARD | CASH
            String paymentMethodBrand,  // VISA / MASTER / null for CASH
            String paymentLast4,        // null for CASH
            String authorizationCode    // null for CASH
    ) {

        Map<String, Object> uriVars = new HashMap<>();
        uriVars.put("invoiceId", invoiceId);
        uriVars.put("paymentMethodType", defaultVal(paymentMethodType, "CARD"));
        uriVars.put("paymentMethodBrand", defaultVal(paymentMethodBrand, ""));
        uriVars.put("paymentLast4", defaultVal(paymentLast4, ""));
        uriVars.put("authorizationCode", defaultVal(authorizationCode, ""));
        uriVars.put("templateCode", "INVOICE_PURCHASE_COMPLETED");
        uriVars.put("channel", "EMAIL");
        uriVars.put("brandName", "ClubOne");
        uriVars.put("currencyCode", "INR");

        ResponseEntity<NotificationRequestDTO> response =
                restTemplate.getForEntity(
                        invoiceEmailPayloadUrl,
                        NotificationRequestDTO.class,
                        uriVars
                );

        if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
            throw new RuntimeException(
                    "Failed to build invoice email payload for invoiceId=" + invoiceId
            );
        }

        return response.getBody();
    }

    /**
     * Step-2:
     * Call /notification/send
     */
    public void sendInvoiceNotification(NotificationRequestDTO payload) {

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        HttpEntity<NotificationRequestDTO> entity =
                new HttpEntity<>(payload, headers);

        ResponseEntity<String> response =
                restTemplate.exchange(
                        notificationSendUrl,
                        HttpMethod.POST,
                        entity,
                        String.class
                );

        if (!response.getStatusCode().is2xxSuccessful()) {
            throw new RuntimeException(
                    "Notification send failed. status="
                            + response.getStatusCode()
                            + " body="
                            + response.getBody()
            );
        }
    }

    /**
     * Step-3:
     * Convenience wrapper (most common usage)
     */
    public void sendInvoiceEmail(
            UUID invoiceId,
            String paymentMethodType,
            String paymentMethodBrand,
            String paymentLast4,
            String authorizationCode
    ) {

        NotificationRequestDTO payload =
                buildInvoiceEmailPayload(
                        invoiceId,
                        paymentMethodType,
                        paymentMethodBrand,
                        paymentLast4,
                        authorizationCode
                );

        sendInvoiceNotification(payload);
        if(payload.isAccess()) {
        	System.out.println("Welcome mail for employee ");
        	payload.setTemplateCode("WELCOME_MSG");
        	sendInvoiceNotification(payload);
        }
    }

    // -----------------------------------
    // helpers
    // -----------------------------------

    private String defaultVal(String v, String def) {
        return v == null ? def : v;
    }
}

