package io.clubone.transaction.baseobject;

import java.util.Locale;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;

@Component
public class RestResponse {

	@Autowired
	MessageSource messageSource;

	/**
	 * Create failure rest response for service.
	 * 
	 * @param response Object of output class
	 * @param status Http status
	 * @param messageType Type of return response
	 * @param errorCode error code for user message
	 * @param args Argument for user message
	 * @return Rest response in json format
	 */
	public <T extends BaseResponse> ResponseEntity<T> populateFailureResponse(T response, HttpStatus status,
		ExceptionMessageEnum messageType, Integer messageId, String errorCode, Object... args) {
		HttpHeaders header = new HttpHeaders();
		header.add("Content-Type", "application/json; charset=UTF-8");
		response.setMessages(createErrorMessage(messageType, messageId, errorCode, args));
		return new ResponseEntity<>(response, header, status);
	}

	public <T extends BaseResponse> ResponseEntity<T> populateFailureResponse(T response, HttpStatus status,
		ExceptionMessageEnum messageType, Integer messageId, String errorCode, String friendlyMessage) {
		HttpHeaders header = new HttpHeaders();
		header.add("Content-Type", "application/json; charset=UTF-8");
		response.setMessages(createErrorMessage(messageType, messageId, errorCode, friendlyMessage));
		return new ResponseEntity<>(response, header, status);
	}

	public <T extends BaseResponse> ResponseEntity<T> populateFailureResponse(T response, HttpStatus status,
		ExceptionMessageEnum messageType, String errorCode, Object... args) {
		HttpHeaders header = new HttpHeaders();
		header.add("Content-Type", "application/json; charset=UTF-8");
		response.setMessages(createErrorMessage(messageType, errorCode, args));
		return new ResponseEntity<>(response, header, status);
	}

	public <T extends BaseRespWithMsgIdAndError> ResponseEntity<T> populateFailureResponseWithIdAndReason(T response,
		HttpStatus status, Integer messageId, String errorCode) {
		HttpHeaders header = new HttpHeaders();
		header.add("Content-Type", "application/json; charset=UTF-8");
		response.setMessages(createErrorMessageWithIdAndReason(messageId, errorCode));
		return new ResponseEntity<>(response, header, status);
	}

	/**
	 * Create success rest response for service.
	 * 
	 * @param response Object of output class
	 * @param status Http status
	 * @return Rest response in json format
	 */
	public <T extends BaseResponse> ResponseEntity<T> populateSuccessResponse(T response, HttpStatus status) {
		return new ResponseEntity<>(response, status);
	}

	public <T extends BaseRespWithMsgIdAndError> ResponseEntity<T> populateSuccessResponseForBaseRespWithMsgIdAndError(
		T response, HttpStatus status) {
		return new ResponseEntity<>(response, status);
	}

	/**
	 * Create success rest response for service.
	 * 
	 * @param response Object of output class
	 * @param status Http status
	 * @return Rest response in json format
	 */
	public <T extends BaseResponse> ResponseEntity<T> populateSuccessResponse(T response, HttpHeaders headers,
		HttpStatus status) {
		return new ResponseEntity<>(response, headers, status);
	}

	public <T extends BaseRespWithMsgIdAndError> ResponseEntity<T> populateSuccessResponseForBaseRespWithMsgIdAndError(
		T response, HttpHeaders headers, HttpStatus status) {
		return new ResponseEntity<>(response, headers, status);
	}

	/**
	 * Create error message
	 *
	 * @param messageType
	 * @param errorCode
	 * @param args
	 * @return
	 */
	private ExceptionMessage[] createErrorMessage(ExceptionMessageEnum messageType, Integer messageId, String errorCode,
		Object... args) {
		return new ExceptionMessage[] {createExceptionMessage(messageType, messageId, errorCode, args)};
	}

	private ExceptionMessage[] createErrorMessage(ExceptionMessageEnum messageType, Integer messageId, String errorCode,
		String friendlyMessage) {
		return new ExceptionMessage[] {createExceptionMessage(messageType, messageId, errorCode, friendlyMessage)};
	}

	private ExceptionMessage[] createErrorMessage(ExceptionMessageEnum messageType, String errorCode, Object... args) {
		return new ExceptionMessage[] {createExceptionMessage(messageType, errorCode, args)};
	}

	private ExceptionMsgWithMsgIdAndError[] createErrorMessageWithIdAndReason(Integer messageId, String errorCode) {
		return new ExceptionMsgWithMsgIdAndError[] {createExceptionMessageWithIdAndReason(messageId, errorCode)};
	}

	/**
	 * Create Exception message
	 * 
	 * @param messageType
	 * @param errorCode
	 * @param args
	 * @return
	 */
	private ExceptionMessage createExceptionMessage(ExceptionMessageEnum messageType, Integer messageId,
		String errorCode, Object... args) {
		ExceptionMessage exceptionMessage = new ExceptionMessage();
		exceptionMessage.setMessageID(messageId);
		exceptionMessage.setMessageType(messageType.name());
		exceptionMessage.setErrorMessage(errorCode);
		exceptionMessage.setFriendlyMessage(getMessage(errorCode, args));
		return exceptionMessage;
	}

	private ExceptionMsgWithMsgIdAndError createExceptionMessageWithIdAndReason(Integer messageId, String errorCode) {
		ExceptionMsgWithMsgIdAndError exceptionMessage = new ExceptionMsgWithMsgIdAndError();
		exceptionMessage.setMessageID(messageId);
		exceptionMessage.setErrorMessage(errorCode);
		return exceptionMessage;
	}

	/**
	 * Create Exception message with a specific friendlyMessage
	 * 
	 * @param messageType
	 * @param messageId
	 * @param errorCode
	 * @param friendlyMessage
	 * @return
	 */
	private ExceptionMessage createExceptionMessage(ExceptionMessageEnum messageType, Integer messageId,
		String errorCode, String friendlyMessage) {
		ExceptionMessage exceptionMessage = new ExceptionMessage();
		exceptionMessage.setMessageID(messageId);
		exceptionMessage.setMessageType(messageType.name());
		exceptionMessage.setErrorMessage(errorCode);
		exceptionMessage.setFriendlyMessage(friendlyMessage);
		return exceptionMessage;
	}

	private ExceptionMessage createExceptionMessage(ExceptionMessageEnum messageType, String errorCode,
		Object... args) {
		return createExceptionMessage(messageType, null, errorCode, args);
	}

	/**
	 * @param code
	 * @param args
	 * @return
	 */
	public String getMessage(String code, Object... args) {
		return messageSource.getMessage(code, args, getLocale());
	}

	/**
	 * @return
	 */
	private Locale getLocale() {
		return LocaleContextHolder.getLocale();
	}
}
