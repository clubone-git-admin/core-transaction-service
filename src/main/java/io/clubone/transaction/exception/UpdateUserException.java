package io.clubone.transaction.exception;

public class UpdateUserException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public UpdateUserException(String message) {
        super(message);
    }
}
