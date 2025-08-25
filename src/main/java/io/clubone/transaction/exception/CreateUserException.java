package io.clubone.transaction.exception;

public class CreateUserException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public CreateUserException(String message) {
        super(message);
    }
}
