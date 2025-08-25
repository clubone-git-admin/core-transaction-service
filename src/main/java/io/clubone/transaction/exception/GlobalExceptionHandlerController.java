package io.clubone.transaction.exception;

import java.util.Objects;

import org.apache.commons.lang3.StringUtils;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.client.HttpClientErrorException.Forbidden;
import org.springframework.web.client.HttpClientErrorException.Unauthorized;
import org.springframework.web.client.HttpServerErrorException.InternalServerError;
import org.springframework.web.client.HttpServerErrorException.ServiceUnavailable;

import lombok.extern.slf4j.Slf4j;

//@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandlerController {

	private final static String EXCEPTION_NAME = "inside handleOnRunTimeExceptions method, exception name is :";

	@ExceptionHandler(value = RuntimeException.class)
	public ProblemDetail handleOnRunTimeExceptions(RuntimeException exception) {
		ProblemDetail problemDetail = null;
		int statusCode = 400;
		if (exception instanceof ResourceNotFoundException) {
			problemDetail = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, exception.getMessage());
			statusCode = 404;
			log.error(EXCEPTION_NAME + "ResourceNotFoundException and statusCode is {}", statusCode);
		} else if (exception instanceof NotValidException) {
			problemDetail = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, exception.getMessage());
			statusCode = 400;
			log.error(EXCEPTION_NAME + "NotValidException and statusCode is {}", statusCode);
		} else if (exception instanceof InternalServerError) {
			problemDetail = ProblemDetail.forStatusAndDetail(HttpStatus.INTERNAL_SERVER_ERROR, exception.getMessage());
			statusCode = 500;
			log.error(EXCEPTION_NAME + "Internal Server Error and statusCode is {}", statusCode);
		} else if (exception instanceof Unauthorized) {
			problemDetail = ProblemDetail.forStatusAndDetail(HttpStatus.UNAUTHORIZED, exception.getMessage());
			statusCode = 401;
			log.error(EXCEPTION_NAME + "Unauthorized and statusCode is {}", statusCode);
		} else if (exception instanceof Forbidden) {
			problemDetail = ProblemDetail.forStatusAndDetail(HttpStatus.FORBIDDEN, exception.getMessage());
			statusCode = 403;
			log.error(EXCEPTION_NAME + "Forbidden and statusCode is {}", statusCode);
		} else if (exception instanceof ServiceUnavailable) {
			problemDetail = ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE, exception.getMessage());
			statusCode = 503;
			log.error(EXCEPTION_NAME + "ServiceUnavailable and statusCode is {}", statusCode);
		} else if (exception instanceof RuntimeException) {
			problemDetail = ProblemDetail.forStatusAndDetail(HttpStatus.INTERNAL_SERVER_ERROR, exception.getMessage());
			statusCode = 503;
			log.error(EXCEPTION_NAME + "ServiceUnavailable and statusCode is {}", statusCode);
		}
		if (Objects.nonNull(problemDetail)) {
			problemDetail.setDetail(exception.getMessage());
			problemDetail.setStatus(statusCode);
		}
		return problemDetail;
	}

	@ResponseStatus(HttpStatus.BAD_REQUEST)
	@ExceptionHandler(MethodArgumentNotValidException.class)
	public ProblemDetail handleValidationExceptions(MethodArgumentNotValidException ex) {
		StringBuilder stringBuilder = new StringBuilder();
		ex.getBindingResult().getAllErrors().forEach(obj -> {
			stringBuilder.append(((FieldError) obj).getField() + " : " + obj.getDefaultMessage() + ",");
		});
		return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, StringUtils.chop(stringBuilder.toString()));
	}
}
