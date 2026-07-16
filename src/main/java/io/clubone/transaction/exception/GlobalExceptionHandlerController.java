package io.clubone.transaction.exception;

import java.util.Objects;

import org.apache.commons.lang3.StringUtils;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
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
import org.springframework.web.server.ResponseStatusException;

import io.clubone.transaction.security.ForbiddenException;
import io.clubone.transaction.security.UnauthorizedException;
import lombok.extern.slf4j.Slf4j;

@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandlerController {

	private static final String EXCEPTION_NAME = "Unhandled exception: ";

	@ExceptionHandler(UnauthorizedException.class)
	public ProblemDetail handleUnauthorized(UnauthorizedException exception) {
		log.warn("Unauthorized: {}", exception.getMessage());
		return ProblemDetail.forStatusAndDetail(HttpStatus.UNAUTHORIZED, exception.getMessage());
	}

	@ExceptionHandler(ForbiddenException.class)
	public ProblemDetail handleForbidden(ForbiddenException exception) {
		log.warn("Forbidden: {}", exception.getMessage());
		return ProblemDetail.forStatusAndDetail(HttpStatus.FORBIDDEN, exception.getMessage());
	}

	@ExceptionHandler(value = RuntimeException.class)
	public ProblemDetail handleOnRunTimeExceptions(RuntimeException exception) {

		ProblemDetail problemDetail = null;
		int statusCode = 400;

		if (exception instanceof ResourceNotFoundException) {

			problemDetail = ProblemDetail.forStatusAndDetail(
					HttpStatus.NOT_FOUND,
					exception.getMessage()
			);

			statusCode = HttpStatus.NOT_FOUND.value();

			log.error(
					EXCEPTION_NAME
							+ "ResourceNotFoundException and statusCode is {}",
					statusCode,
					exception
			);

		} else if (exception instanceof NotValidException) {

			problemDetail = ProblemDetail.forStatusAndDetail(
					HttpStatus.BAD_REQUEST,
					exception.getMessage()
			);

			statusCode = HttpStatus.BAD_REQUEST.value();

			log.error(
					EXCEPTION_NAME
							+ "NotValidException and statusCode is {}",
					statusCode,
					exception
			);

		} else if (exception instanceof ResponseStatusException responseStatusException) {

			/*
			 * Preserve the HTTP status and friendly reason supplied by the
			 * service/validator instead of converting it to 503.
			 */
			HttpStatusCode responseStatus =
					responseStatusException.getStatusCode();

			statusCode = responseStatus.value();

			String detail =
					StringUtils.isNotBlank(
							responseStatusException.getReason()
					)
							? responseStatusException.getReason()
							: responseStatusException.getMessage();

			problemDetail = ProblemDetail.forStatusAndDetail(
					responseStatus,
					detail
			);

			log.error(
					EXCEPTION_NAME
							+ "ResponseStatusException and statusCode is {}",
					statusCode,
					exception
			);

		} else if (exception instanceof InternalServerError) {

			problemDetail = ProblemDetail.forStatusAndDetail(
					HttpStatus.INTERNAL_SERVER_ERROR,
					exception.getMessage()
			);

			statusCode =
					HttpStatus.INTERNAL_SERVER_ERROR.value();

			log.error(
					EXCEPTION_NAME
							+ "Internal Server Error and statusCode is {}",
					statusCode,
					exception
			);

		} else if (exception instanceof Unauthorized) {

			problemDetail = ProblemDetail.forStatusAndDetail(
					HttpStatus.UNAUTHORIZED,
					exception.getMessage()
			);

			statusCode = HttpStatus.UNAUTHORIZED.value();

			log.error(
					EXCEPTION_NAME
							+ "Unauthorized and statusCode is {}",
					statusCode,
					exception
			);

		} else if (exception instanceof Forbidden) {

			problemDetail = ProblemDetail.forStatusAndDetail(
					HttpStatus.FORBIDDEN,
					exception.getMessage()
			);

			statusCode = HttpStatus.FORBIDDEN.value();

			log.error(
					EXCEPTION_NAME
							+ "Forbidden and statusCode is {}",
					statusCode,
					exception
			);

		} else if (exception instanceof ServiceUnavailable) {

			problemDetail = ProblemDetail.forStatusAndDetail(
					HttpStatus.SERVICE_UNAVAILABLE,
					exception.getMessage()
			);

			statusCode =
					HttpStatus.SERVICE_UNAVAILABLE.value();

			log.error(
					EXCEPTION_NAME
							+ "ServiceUnavailable and statusCode is {}",
					statusCode,
					exception
			);

		} else if (exception instanceof RuntimeException) {

			/*
			 * Existing fallback behavior retained.
			 */
			problemDetail = ProblemDetail.forStatusAndDetail(
					HttpStatus.INTERNAL_SERVER_ERROR,
					exception.getMessage()
			);

			statusCode = 503;

			log.error(
					EXCEPTION_NAME
							+ "ServiceUnavailable and statusCode is {}",
					statusCode,
					exception
			);
		}

		if (Objects.nonNull(problemDetail)) {
			problemDetail.setStatus(statusCode);

			/*
			 * For ResponseStatusException, use getReason() so the response
			 * does not contain:
			 *
			 * 400 BAD_REQUEST "validation message"
			 *
			 * It will contain only the friendly validation message.
			 */
			if (exception instanceof ResponseStatusException responseStatusException
					&& StringUtils.isNotBlank(
							responseStatusException.getReason()
					)) {

				problemDetail.setDetail(
						responseStatusException.getReason()
				);

			} else {

				problemDetail.setDetail(
						exception.getMessage()
				);
			}
		}

		return problemDetail;
	}

	@ResponseStatus(HttpStatus.BAD_REQUEST)
	@ExceptionHandler(MethodArgumentNotValidException.class)
	public ProblemDetail handleValidationExceptions(
			MethodArgumentNotValidException ex
	) {
		StringBuilder stringBuilder =
				new StringBuilder();

		ex.getBindingResult()
				.getAllErrors()
				.forEach(obj -> {
					stringBuilder.append(
							((FieldError) obj).getField()
									+ " : "
									+ obj.getDefaultMessage()
									+ ","
					);
				});

		return ProblemDetail.forStatusAndDetail(
				HttpStatus.BAD_REQUEST,
				StringUtils.chop(
						stringBuilder.toString()
				)
		);
	}
}