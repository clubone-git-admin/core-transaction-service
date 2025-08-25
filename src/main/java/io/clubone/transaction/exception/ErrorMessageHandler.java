package io.clubone.transaction.exception;

import java.util.Locale;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.stereotype.Component;

import io.clubone.transaction.baseobject.ExceptionMessage;

@Component
public class ErrorMessageHandler {

	@Autowired
	private MessageSource messageSource;

	public ExceptionMessage[] createExceptionMessageArray(UserAccessExceptionMessage message, String messageType) {
		return new ExceptionMessage[] {createExceptionMessage(message.name(), message.getCode(), messageType)};
	}

	public ExceptionMessage createExceptionMessage(String errorMessage, int errorCode, String errorType,
		Object... args) {
		ExceptionMessage exceptionMessage = new ExceptionMessage();
		exceptionMessage.setErrorMessage(errorMessage);
		exceptionMessage.setFriendlyMessage(getFrindlyMessage(errorMessage, args));
		exceptionMessage.setMessageID(errorCode);
		exceptionMessage.setMessageType(errorType);
		return exceptionMessage;
	}

	public ExceptionMessage createExceptionMessage(String errorMessage, int errorCode, String errorType,
		String friendlyMessage) {
		ExceptionMessage exceptionMessage = new ExceptionMessage();
		exceptionMessage.setErrorMessage(errorMessage);
		exceptionMessage.setFriendlyMessage(friendlyMessage);
		exceptionMessage.setMessageID(errorCode);
		exceptionMessage.setMessageType(errorType);
		return exceptionMessage;
	}

	private String getFrindlyMessage(String errorMessage, Object[] args) {
		return messageSource.getMessage(errorMessage, args, getLocale());
	}

	private Locale getLocale() {
		return LocaleContextHolder.getLocale();
	}

	public ExceptionMessage[] createAndReturnExceptionMessageArray(UserAccessExceptionMessage message,
		String messageType) {
		return new ExceptionMessage[] {createExceptionMessage(message.name(), message.getCode(), messageType)};
	}
}
