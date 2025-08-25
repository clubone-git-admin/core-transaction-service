package io.clubone.transaction.baseobject;

public class BaseRequest {

	protected boolean validationOnly = Boolean.FALSE;

	private boolean isAsyncRequest = Boolean.FALSE;

	private boolean ignoreWarnings = Boolean.FALSE;

	/**
	 * Gets the value of the validationOnly property.
	 */
	public boolean isValidationOnly() {
		return validationOnly;
	}

	/**
	 * Sets the value of the validationOnly property.
	 */
	public void setValidationOnly(boolean value) {
		this.validationOnly = value;
	}

	public boolean isAsyncRequest() {
		return isAsyncRequest;
	}

	public void setAsyncRequest(boolean isAsyncRequest) {
		this.isAsyncRequest = isAsyncRequest;
	}

	public boolean isIgnoreWarnings() {
		return ignoreWarnings;
	}

	public void setIgnoreWarnings(boolean ignoreWarnings) {
		this.ignoreWarnings = ignoreWarnings;
	}
}
