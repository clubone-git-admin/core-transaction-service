package io.clubone.transaction.inventory;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestTemplate;

@Configuration
@EnableConfigurationProperties(
        InventoryProvisioningProperties.class
)
public class ClientInventoryIntegrationConfig {

    @Bean
    public ClientInventoryApiClient clientInventoryApiClient(
            RestTemplateBuilder builder,
            InventoryProvisioningProperties properties,
            ObjectMapper objectMapper) {

        RestTemplate clientInventoryRestTemplate = builder
                .rootUri(properties.baseUrl().toString())
                .setConnectTimeout(properties.connectTimeout())
                .setReadTimeout(properties.readTimeout())
                .build();

        return new ClientInventoryApiClient(
                clientInventoryRestTemplate,
                objectMapper
        );
    }
}