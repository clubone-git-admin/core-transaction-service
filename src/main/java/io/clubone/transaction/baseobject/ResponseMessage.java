package io.clubone.transaction.baseobject;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import lombok.Data;

@JsonIgnoreProperties(ignoreUnknown = true)
@Data
public class ResponseMessage {

	Integer messageId;

	String message;

	String friendlyMessage;

	String messageType;

	String stackTrace;
}
