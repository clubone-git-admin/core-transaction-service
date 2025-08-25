package io.clubone.transaction.validator;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;


@Component
public class RequestValidator {

	private static final Logger logger = LoggerFactory.getLogger(RequestValidator.class);

	private static final String EMAIL_REGEX = "^(.+)@(.+)$";

	private static final String ACCESSNAME_REGEX = "^(.+)@(.+)$";
}
