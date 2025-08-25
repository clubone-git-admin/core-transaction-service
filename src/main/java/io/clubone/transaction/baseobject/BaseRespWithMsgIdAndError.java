package io.clubone.transaction.baseobject;

import java.io.Serializable;

public class BaseRespWithMsgIdAndError implements Serializable {

	private static final long serialVersionUID = 6440500013720128156L;

	private ExceptionMsgWithMsgIdAndError[] messages;

	public final ExceptionMsgWithMsgIdAndError[] getMessages() {
		return messages;
	}

	public final void setMessages(ExceptionMsgWithMsgIdAndError[] messages) {
		this.messages = messages;
	}
}
