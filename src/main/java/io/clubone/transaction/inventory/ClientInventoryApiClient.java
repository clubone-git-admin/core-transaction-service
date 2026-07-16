package io.clubone.transaction.inventory;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@Slf4j
public class ClientInventoryApiClient {

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;
    
    private static final UUID TEST_ACTOR_ID =
            UUID.fromString(
                    "1934776b-1912-4886-9890-023f21f6ba3b"
            );

    private static final UUID TEST_HEADER_LOCATION_ID =
            UUID.fromString(
                    "290ea7fa-7842-44ba-bf09-578c6e8a7842"
            );

    public ClientInventoryApiClient(
            RestTemplate restTemplate,
            ObjectMapper objectMapper) {

        this.restTemplate = restTemplate;
        this.objectMapper = objectMapper;
    }

    public UUID createInventory(
            UUID clientRoleId,
            UUID actorId,
            UUID locationId,
            String correlationId,
            CreateClientInventoryItemRequest request) {

        String endpoint =
                "/client-inventory/api/clients/"
                        + clientRoleId
                        + "/inventory";

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setAccept(
                List.of(MediaType.APPLICATION_JSON)
        );

        if (request.applicationId() == null) {
            throw new InventoryProvisioningException(
                    "applicationId is required for client inventory creation"
            );
        }

        headers.set(
                "application-id",
                request.applicationId().toString()
        );

        headers.set(
                "x-actor-id",
                TEST_ACTOR_ID.toString()
        );

        headers.set(
                "x-location-id",
                TEST_HEADER_LOCATION_ID.toString()
        );

        headers.set(
                "X-Correlation-Id",
                correlationId
        );

        HttpEntity<CreateClientInventoryItemRequest> entity =
                new HttpEntity<>(
                        request,
                        headers
                );

        /*
         * Logging the exact request being sent.
         *
         * Do not log authentication tokens, API keys or card details
         * if they are added to this API in the future.
         */
        log.info(
                "[client-inventory/create] "
                        + "step=request "
                        + "endpoint={} "
                        + "clientRoleId={} "
                        + "applicationId={} "
                        + "headerActorId={} "
                        + "headerLocationId={} "
                        + "correlationId={} "
                        + "payload={}",
                endpoint,
                clientRoleId,
                request.applicationId(),
                TEST_ACTOR_ID,
                TEST_HEADER_LOCATION_ID,
                correlationId,
                toJson(request)
        );

        try {
            ResponseEntity<
                    ClientInventoryApiResponse<
                            Map<String, UUID>>> response =
                    restTemplate.exchange(
                            endpoint,
                            HttpMethod.POST,
                            entity,
                            new ParameterizedTypeReference<>() {
                            }
                    );

            ClientInventoryApiResponse<Map<String, UUID>>
                    body = response.getBody();

            log.info(
                    "[client-inventory/create] "
                            + "step=response "
                            + "endpoint={} "
                            + "httpStatus={} "
                            + "clientRoleId={} "
                            + "correlationId={} "
                            + "responseBody={}",
                    endpoint,
                    response.getStatusCode().value(),
                    clientRoleId,
                    correlationId,
                    toJson(body)
            );

            if (body == null
                    || !body.success()
                    || body.data() == null
                    || body.data()
                            .get("clientInventoryItemId")
                            == null) {

                throw new InventoryProvisioningException(
                        firstNonBlank(
                                body == null
                                        ? null
                                        : body.message(),
                                "Client inventory API returned "
                                        + "an invalid success response."
                        )
                );
            }

            UUID clientInventoryItemId =
                    body.data()
                            .get("clientInventoryItemId");

            log.info(
                    "[client-inventory/create] "
                            + "step=complete "
                            + "outcome=success "
                            + "clientRoleId={} "
                            + "clientInventoryItemId={} "
                            + "correlationId={}",
                    clientRoleId,
                    clientInventoryItemId,
                    correlationId
            );

            return clientInventoryItemId;

        } catch (HttpStatusCodeException ex) {
            String responseBody =
                    ex.getResponseBodyAsString();

            String message =
                    extractErrorMessage(responseBody);

            log.error(
                    "[client-inventory/create] "
                            + "step=response "
                            + "outcome=http_error "
                            + "endpoint={} "
                            + "httpStatus={} "
                            + "clientRoleId={} "
                            + "actorId={} "
                            + "locationId={} "
                            + "correlationId={} "
                            + "requestPayload={} "
                            + "responseBody={}",
                    endpoint,
                    ex.getStatusCode().value(),
                    clientRoleId,
                    actorId,
                    locationId,
                    correlationId,
                    toJson(request),
                    responseBody,
                    ex
            );

            throw new InventoryProvisioningException(
                    firstNonBlank(
                            message,
                            "Client inventory API failed "
                                    + "with status "
                                    + ex.getStatusCode().value()
                    ),
                    ex
            );

        } catch (InventoryProvisioningException ex) {
            log.error(
                    "[client-inventory/create] "
                            + "step=processing "
                            + "outcome=validation_error "
                            + "endpoint={} "
                            + "clientRoleId={} "
                            + "correlationId={} "
                            + "message={}",
                    endpoint,
                    clientRoleId,
                    correlationId,
                    ex.getMessage(),
                    ex
            );

            throw ex;

        } catch (RestClientException ex) {
            log.error(
                    "[client-inventory/create] "
                            + "step=call "
                            + "outcome=connection_error "
                            + "endpoint={} "
                            + "clientRoleId={} "
                            + "actorId={} "
                            + "locationId={} "
                            + "correlationId={} "
                            + "requestPayload={} "
                            + "message={}",
                    endpoint,
                    clientRoleId,
                    actorId,
                    locationId,
                    correlationId,
                    toJson(request),
                    ex.getMessage(),
                    ex
            );

            throw new InventoryProvisioningException(
                    "Unable to call the client inventory API.",
                    ex
            );
        }
    }

    private String extractErrorMessage(String body) {
        if (body == null || body.isBlank()) {
            return null;
        }

        try {
            ClientInventoryApiResponse<Object> response =
                    objectMapper.readValue(
                            body,
                            new TypeReference<
                                    ClientInventoryApiResponse<
                                            Object>>() {
                            }
                    );

            return response.message();

        } catch (Exception ex) {
            log.warn(
                    "[client-inventory/create] "
                            + "Unable to parse error response. "
                            + "responseBody={}",
                    body
            );

            return null;
        }
    }

    private String toJson(Object value) {
        if (value == null) {
            return "null";
        }

        try {
            return objectMapper.writeValueAsString(value);

        } catch (JsonProcessingException ex) {
            log.warn(
                    "[client-inventory/create] "
                            + "Unable to serialize log payload. "
                            + "type={} message={}",
                    value.getClass().getName(),
                    ex.getMessage()
            );

            return value.toString();
        }
    }

    private String firstNonBlank(
            String first,
            String fallback) {

        return first == null || first.isBlank()
                ? fallback
                : first.trim();
    }
}