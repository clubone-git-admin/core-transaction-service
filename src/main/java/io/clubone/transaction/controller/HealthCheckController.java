package io.clubone.transaction.controller;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.info.BuildProperties;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.clubone.transaction.baseobject.Docs;
import io.clubone.transaction.baseobject.Version;
import io.swagger.annotations.ApiResponse;
import io.swagger.annotations.ApiResponses;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;

@RestController
@Slf4j
@Tag(name = "Health Check", description = "View, configured health checkings data")
public class HealthCheckController {

	@Autowired
	private BuildProperties buildProperties;

	@GetMapping({"/health"})
	public Version getVersion(HttpServletRequest httpServletRequest) {
		log.debug("inside getVersion() method start");
		Docs docs = new Docs();
		docs.setStatus("Live - " + this.buildProperties.get("time"));
		docs.setUrl(httpServletRequest.getRequestURL().toString().replace("version", "swagger-ui.html"));
		Version version = new Version();
		version.setVersion(getClass().getPackage().getImplementationVersion());
		version.setDocs(docs);
		log.debug("inside getVersion() method end");
		return version;
	}
}
