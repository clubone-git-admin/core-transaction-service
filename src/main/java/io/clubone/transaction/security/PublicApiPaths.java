package io.clubone.transaction.security;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Paths that do not require tenant headers (health, docs, external webhooks).
 */
public final class PublicApiPaths {

  private PublicApiPaths() {
  }

  public static boolean isPublic(HttpServletRequest request) {
    return isPublicPath(resolvePath(request), request.getMethod());
  }

  public static boolean isPublicPath(String path, String method) {
    if (path == null || path.isEmpty()) {
      return false;
    }
    if (path.equals("/health") || path.startsWith("/health/")
        || path.startsWith("/actuator/")
        || path.equals("/swagger-ui.html")
        || path.startsWith("/swagger-ui/")
        || path.startsWith("/docs")
        || path.equals("/v3/api-docs")
        || path.startsWith("/v3/api-docs/")) {
      return true;
    }
    // Meta WhatsApp webhook (verify + receive)
    if (path.equals("/webhook") || path.startsWith("/webhook/")) {
      return true;
    }
    return false;
  }

  public static String resolvePath(HttpServletRequest request) {
    String servletPath = request.getServletPath();
    if (servletPath != null && !servletPath.isEmpty()) {
      return servletPath;
    }
    String uri = request.getRequestURI();
    String context = request.getContextPath();
    if (context != null && !context.isEmpty() && uri.startsWith(context)) {
      return uri.substring(context.length());
    }
    return uri;
  }
}
