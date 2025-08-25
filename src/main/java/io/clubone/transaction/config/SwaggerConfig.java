package io.clubone.transaction.config;

import java.util.function.Supplier;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.swagger.v3.oas.models.ExternalDocumentation;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;

@Configuration
public class SwaggerConfig {

	@Value("${swagger.title}")
	private String title;

	@Value("${swagger.description}")
	private String description;

	@Value("${swagger.version}")
	private String version;

	@Value("${swagger.termsOfServiceUrl}")
	private String termsOfServiceUrl;

	@Value("${swagger.contact.name}")
	private String contactName;

	@Value("${swagger.contact.url}")
	private String contactURL;

	@Value("${swagger.contact.email}")
	private String contactEmail;

	@Value("${swagger.license}")
	private String license;

	@Value("${swagger.licenseUrl}")
	private String licenseURL;

	@Bean
	public OpenAPI api() {
		return new OpenAPI()
			.info(new Info().title(title).description(description).contact(this.getContact().get()).version(version)
				.license(new License().name(license).url(licenseURL)))
			.externalDocs(new ExternalDocumentation().description(description).url(contactURL));
	}

	/**
	 * This method will return the API info object to swagger which will in turn display the information on the swagger
	 * UI.
	 * 
	 * @return the API information
	 */
	private Supplier<Contact> getContact() {
		return () -> {
			Contact contact = new Contact();
			contact.setName(contactName);
			contact.setEmail(contactEmail);
			contact.setUrl(contactURL);
			return contact;
		};
	}
}
