package io.clubone.transaction.baseobject;

import java.io.Serializable;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public class ExceptionMessageJson implements Serializable {

	private static final long serialVersionUID = 1L;

	@JsonProperty("messageID")
	private Integer messageID;

	@JsonProperty("errorMessage")
	private String errorMessage;

	@JsonProperty("friendlyMessage")
	private String friendlyMessage;

	@JsonProperty("messageType")
	private String messageType;

	public Integer getMessageID() {
		return messageID;
	}

	public void setMessageID(Integer messageID) {
		this.messageID = messageID;
	}

	public String getErrorMessage() {
		return errorMessage;
	}

	public void setErrorMessage(String errorMessage) {
		this.errorMessage = errorMessage;
	}

	public String getFriendlyMessage() {
		return friendlyMessage;
	}

	public void setFriendlyMessage(String friendlyMessage) {
		this.friendlyMessage = friendlyMessage;
	}

	public String getMessageType() {
		return messageType;
	}

	public void setMessageType(String messageType) {
		this.messageType = messageType;
	}

	public ExceptionMessageJson() {
	}

	public ExceptionMessageJson(Integer messageID, String errorMessage, String friendlyMessage, String messageType) {
		this.messageID = messageID;
		this.errorMessage = errorMessage;
		this.friendlyMessage = friendlyMessage;
		this.messageType = messageType;
	}
}
