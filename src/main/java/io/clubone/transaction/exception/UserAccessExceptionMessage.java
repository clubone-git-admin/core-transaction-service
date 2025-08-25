package io.clubone.transaction.exception;

public enum UserAccessExceptionMessage {
	NO_CODE(0),
	USER_NAME_IS_MISSING(10001),
	INVALID_EMAIL(10002),
	PASSWORD_REQUIRED(10003),
	NO_USER_ACCOUNT_FOUND(10004),
	USER_ACCOUNT_NOT_ACTIVE(10005),
	USER_ACCOUNT_LOCKED(10006),
	PASSWORD_SALT_NOT_FOUND(10007),
	INVALID_CREDENTIALS(10008),
	UKG_ID_MISSING(10009),
	LAST_NAME_IS_MISSING(10011),
	FIRST_NAME_IS_MISSING(10010),
	EXTERNAL_ID_MISSING(10012),
	APPLICATION_ID_NOT_VALID(10013),
	GENDER_VALIDATION(10014),
	CREATED_BY_IS_REQUIRED_PARAM(10015),
	MODIFIED_BY_IS_REQUIRED_PARAM(10016),
	TOKEN_GENERATION_FAILED(20001),

	FAILED_TO_UPDATE_USER_DETAILS(10017),

	ACCESSES_NOT_VALID(10018),

	LOCATION_ID_REQUIRED(10019),

	FAILED_PROCESS_REQUEST(10020),

	INVALID_LOCATION(10021),

	INVALID_APPLICATION(10022),

	LOGIN_REQUEST_FAILED(10023);

	private int code;

	public int getCode() {
		return code;
	}

	private UserAccessExceptionMessage(int code) {
		this.code = code;
	}

	public static UserAccessExceptionMessage valueOf(int code) {
		UserAccessExceptionMessage[] exceptionMessages = values();
		for (UserAccessExceptionMessage message : exceptionMessages) {
			if (message.getCode() == code) {
				return message;
			}
		}
		return NO_CODE;
	}
}
