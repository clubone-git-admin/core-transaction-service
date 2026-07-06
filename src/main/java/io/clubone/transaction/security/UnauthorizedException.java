package io.clubone.transaction.security;

public class UnauthorizedException extends RuntimeException {
  public UnauthorizedException(String msg) { super(msg); }
}
