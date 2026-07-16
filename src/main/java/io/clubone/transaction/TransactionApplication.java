package io.clubone.transaction;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.info.BuildProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.transaction.annotation.EnableTransactionManagement;

import io.clubone.transaction.config.RestTemplateConfig;
import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.info.Info;
import lombok.extern.slf4j.Slf4j;

import java.util.Properties;

@SpringBootApplication
@EnableScheduling
@EnableTransactionManagement
@Import(RestTemplateConfig.class)
@OpenAPIDefinition(info = @Info(
    title = "ClubOne Transaction API",
    version = "3.0",
    description = "Invoicing, subscriptions, POS catalog, GL posting, and transaction finalization"))
@Slf4j
public class TransactionApplication {

	public static void main(String[] args) {
		SpringApplication.run(TransactionApplication.class, args);
		log.info("Transaction service started");
	}

	@Bean
	public BuildProperties buildProperties() {
		return new BuildProperties(new Properties());
	}
}
