package io.clubone.transaction.baseobject;
public class CluboneDataAccessException extends RuntimeException {

	private static final long serialVersionUID = 1L;

	String errorMessage;

	Object args[];

	Integer messageId;

	public CluboneDataAccessException(String errorMessage, Integer messageId) {
		this.errorMessage = errorMessage;
		this.messageId = messageId;
	}

	public CluboneDataAccessException(String errorMessage, Integer messageId, Throwable cause) {
		super(errorMessage, cause);
		this.errorMessage = errorMessage;
		this.messageId = messageId;
	}

	public CluboneDataAccessException(String errorMessage, Integer messageId, Object... args) {
		this.errorMessage = errorMessage;
		this.messageId = messageId;
		this.args = args;
	}

	public CluboneDataAccessException(String errorMessage, Integer messageId, Throwable cause, Object... args) {
		super(errorMessage, cause);
		this.errorMessage = errorMessage;
		this.messageId = messageId;
		this.args = args;
	}

	public String getErrorMessage() {
		return errorMessage;
	}

	public void setErrorMessage(String errorMessage) {
		this.errorMessage = errorMessage;
	}

	public Object[] getArgs() {
		return args;
	}

	public void setArgs(Object[] args) {
		this.args = args;
	}

	public Integer getMessageId() {
		return messageId;
	}

	public void setMessageId(Integer messageId) {
		this.messageId = messageId;
	}
}
