package io.clubone.transaction.baseobject;

import java.io.Serializable;

public class BaseResponse implements Serializable {

	private static final long serialVersionUID = 1L;

	private boolean successFlag = Boolean.TRUE;

	private ExceptionMessage[] messages;

	public final ExceptionMessage[] getMessages() {
		return messages;
	}

	public final void setMessages(ExceptionMessage[] messages) {
		this.messages = messages;
	}
}
