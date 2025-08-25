package io.clubone.transaction.exception;

public enum ExceptionType {

	ERROR("error"),
	VALIDATION("validation");

	private String type;

	public String getType() {
		return type;
	}

	private ExceptionType(String type) {
		this.type = type;
	}
}
