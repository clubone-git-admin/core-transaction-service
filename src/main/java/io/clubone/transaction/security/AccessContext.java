package io.clubone.transaction.security;

import java.util.UUID;
import java.util.function.Supplier;

public final class AccessContext {

  /**
   * Used only for public / remote-close invoice flows where {@link TenantContext} is intentionally
   * absent (customer has no staff {@code X-Actor-Id}). Staff paths always prefer TenantContext.
   */
  private static final ThreadLocal<UUID> APPLICATION_ID_OVERRIDE = new ThreadLocal<>();

  private AccessContext() {
  }

  public static TenantContext require() {
    TenantContext ctx = TenantContext.get();
    if (ctx == null) {
      throw new UnauthorizedException("Actor context is required");
    }
    return ctx;
  }

  public static UUID requireApplicationId(String applicationIdHeader) {
    TenantContext ctx = require();
    if (applicationIdHeader == null || applicationIdHeader.isBlank()) {
      throw new UnauthorizedException("application-id header is required");
    }
    UUID headerAppId;
    try {
      headerAppId = UUID.fromString(applicationIdHeader.trim());
    } catch (IllegalArgumentException ex) {
      throw new UnauthorizedException("Invalid application-id header");
    }
    if (!ctx.applicationId().equals(headerAppId)) {
      throw new ForbiddenException("application-id does not match authenticated actor");
    }
    return headerAppId;
  }

  public static UUID actorUserId() {
    return require().userId();
  }

  public static UUID actorApplicationUserId() {
    return require().applicationUserId();
  }

  public static UUID workingLocationId() {
    UUID loc = require().workingLocation();
    if (loc == null) {
      throw new ForbiddenException("X-Location-Id is required for this operation");
    }
    return loc;
  }

  public static UUID applicationId() {
    TenantContext ctx = TenantContext.get();
    if (ctx != null) {
      return ctx.applicationId();
    }
    UUID override = APPLICATION_ID_OVERRIDE.get();
    if (override != null) {
      return override;
    }
    throw new UnauthorizedException("Actor context is required");
  }

  /**
   * Runs {@code action} with a temporary application-id override when the caller is a public
   * remote-close purchaser (no TenantContext). No-op override when {@code applicationId} is null.
   */
  public static <T> T callWithApplicationIdOverride(UUID applicationId, Supplier<T> action) {
    if (applicationId == null) {
      return action.get();
    }
    UUID previous = APPLICATION_ID_OVERRIDE.get();
    APPLICATION_ID_OVERRIDE.set(applicationId);
    try {
      return action.get();
    } finally {
      if (previous != null) {
        APPLICATION_ID_OVERRIDE.set(previous);
      } else {
        APPLICATION_ID_OVERRIDE.remove();
      }
    }
  }

  static void setApplicationIdOverride(UUID applicationId) {
    APPLICATION_ID_OVERRIDE.set(applicationId);
  }

  static void clearApplicationIdOverride() {
    APPLICATION_ID_OVERRIDE.remove();
  }
}
