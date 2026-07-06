package io.clubone.transaction.security;

public class ForbiddenException extends RuntimeException {
  public ForbiddenException(String msg) { super(msg); }
}
