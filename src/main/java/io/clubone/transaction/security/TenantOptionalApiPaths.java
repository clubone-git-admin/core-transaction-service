package io.clubone.transaction.security;

/**
 * POS checkout paths where {@code X-Actor-Id}, {@code X-Location-Id}, and
 * {@code application-id} are optional. When provided, they are validated normally.
 */
public final class TenantOptionalApiPaths {

  private TenantOptionalApiPaths() {
  }

  public static boolean isOptional(String path, String method) {
    if (path == null || path.isEmpty() || method == null) {
      return false;
    }
    if ("POST".equalsIgnoreCase(method) && path.equals("/v2/api/transactions/invoice")) {
      return true;
    }
    if ("POST".equalsIgnoreCase(method) && path.equals("/api/transactions/v3/finalize")) {
      return true;
    }
    return false;
  }
}
