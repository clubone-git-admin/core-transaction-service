package io.clubone.transaction.security;

/**
 * Hard-cutover: no POS paths may skip tenant headers.
 * Gateway/public paths remain via {@link PublicApiPaths} only.
 */
public final class TenantOptionalApiPaths {

  private TenantOptionalApiPaths() {
  }

  public static boolean isOptional(String path, String method) {
    return false;
  }
}
