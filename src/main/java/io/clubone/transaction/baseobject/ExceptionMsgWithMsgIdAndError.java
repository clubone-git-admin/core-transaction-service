package io.clubone.transaction.baseobject;

import java.io.Serializable;

public class ExceptionMsgWithMsgIdAndError implements Serializable {

	private static final long serialVersionUID = 1L;

	private Integer messageID;

	private String errorMessage;

	public ExceptionMsgWithMsgIdAndError(Integer messageID, String errorMessage) {
		super();
		this.messageID = messageID;
		this.errorMessage = errorMessage;
	}

	public ExceptionMsgWithMsgIdAndError() {
	}

	@Override
	public boolean equals(Object obj) {
		if (this == obj)
			return true;
		if (obj == null)
			return false;
		if (getClass() != obj.getClass())
			return false;
		final ExceptionMsgWithMsgIdAndError other = (ExceptionMsgWithMsgIdAndError) obj;
		if (messageID == null) {
			if (other.messageID != null)
				return false;
		} else if (!messageID.equals(other.messageID))
			return false;
		return true;
	}

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
}
