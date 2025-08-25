package io.clubone.transaction.exception;

public enum AccessUserExceptionMessage {

    NO_CODE(0);
    private int code;

    public int getCode() {
        return code;
    }

    private AccessUserExceptionMessage(int code) {
        this.code = code;
    }

    public static AccessUserExceptionMessage valueOf(int code) {
        AccessUserExceptionMessage[] exceptionMessages = values();
        for (AccessUserExceptionMessage message : exceptionMessages) {
            if (message.getCode() == code) {
                return message;
            }
        }
        return NO_CODE;
    }
}
