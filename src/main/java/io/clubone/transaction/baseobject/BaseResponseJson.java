package io.clubone.transaction.baseobject;

import java.io.Serializable;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public class BaseResponseJson implements Serializable {

	private static final long serialVersionUID = 1L;

	@JsonProperty("messages")
	private ExceptionMessageJson[] messages;

	public ExceptionMessageJson[] getMessages() {
		return messages;
	}

	public void setMessages(ExceptionMessageJson[] messages) {
		this.messages = messages;
	}
}
