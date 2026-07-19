package io.clubone.transaction.security;

/**
 * Paths that MAY proceed without full tenant headers (X-Actor-Id / X-Location-Id).
 *
 * <p>These are endpoints that a public / remote-close consumer (e.g. the join.clubone.io
 * self-service portal opened from a remote sale link) must be able to call without a staff
 * actor. When the tenant headers ARE present (POS staff), the normal tenant-context path
 * still runs unchanged; only header-less calls fall back to the optional principal.
 */
public final class TenantOptionalApiPaths {

  private TenantOptionalApiPaths() {
  }

  public static boolean isOptional(String path, String method) {
    if (path == null) {
      return false;
    }
    // Public remote-close / join portal: customer completes their own purchase from a remote
    // link and has no staff actor headers. Invoice create + post-payment finalize.
    if ("POST".equalsIgnoreCase(method)
        && (path.equals("/v2/api/transactions/invoice")
            || path.equals("/api/transactions/v3/finalize"))) {
      return true;
    }
    // The post-payment receipt is fetched by the same header-less join portal.
    // It remains application-scoped through AccessContext's application-id override.
    if ("GET".equalsIgnoreCase(method)
        && path.matches("/api/transactions/v3/invoices/[^/]+/receipt")) {
      return true;
    }
    return false;
  }
}
