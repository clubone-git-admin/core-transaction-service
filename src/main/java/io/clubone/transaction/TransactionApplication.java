package io.clubone.transaction;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.info.BuildProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.info.Info;
import lombok.extern.slf4j.Slf4j;

import java.util.Properties;

@SpringBootApplication
@OpenAPIDefinition(info = @Info(title = "clubone Api", version = "2.0", description = "User Access Information"))
@Slf4j
//@EnableTransactionManagement
public class TransactionApplication {

	public static void main(String[] args) {
		SpringApplication.run(TransactionApplication.class, args);
		log.info("... Application started Successfully ...");
	}

	@Bean
	public BuildProperties buildProperties() {
		return new BuildProperties(new Properties());
	}
	
	@Bean
	public WebMvcConfigurer corsConfigurer() {
		return new WebMvcConfigurer() {
			@Override
			public void addCorsMappings(CorsRegistry registry) {
				registry.addMapping("/**") // Adjust the mapping pattern as per your requirements
				.allowedOriginPatterns("*") // Set the allowed origins or "*" for all origins
				.allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS") // Set the allowed HTTP methods
				.allowedHeaders("*")// Set the allowed headers
				.allowCredentials(true); // Allow credentials, if needed
			}
		};
	}
	
	@Bean("userAccessRestTemplate")
	public RestTemplate getRestTemplate() {
		return new RestTemplate();
	}
}
