package io.clubone.transaction.security;

import java.io.IOException;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;

@Component
@Slf4j
public class ExternalContextFilter extends OncePerRequestFilter {

  private static final String READ_SCOPE = "transactions:read";
  private static final String WRITE_SCOPE = "transactions:write";

  @Override
  protected boolean shouldNotFilter(HttpServletRequest req) {
    if ("OPTIONS".equalsIgnoreCase(req.getMethod())) {
      return true;
    }
    String path = resolvePath(req);
    if (isPublicPath(path)) {
      return true;
    }
    String clientId = req.getHeader(ExternalAuth.HEADER_CLIENT_ID);
    return clientId == null || clientId.isBlank();
  }

  @Override
  protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
      throws IOException, ServletException {

    String clientId = req.getHeader(ExternalAuth.HEADER_CLIENT_ID).trim();
    Set<String> scopes = parseScopes(req.getHeader(ExternalAuth.HEADER_SCOPES));
    if (scopes.isEmpty()) {
      FilterErrorWriter.write(res, HttpServletResponse.SC_FORBIDDEN, "forbidden",
          "X-External-Scopes is required for external clients");
      return;
    }

    String appHeader = firstNonBlank(
        req.getHeader(ExternalAuth.HEADER_APPLICATION_ID),
        req.getHeader(ExternalAuth.HEADER_APPLICATION_ID_ALT),
        req.getHeader("X-Application-Id"));
    if (appHeader == null) {
      FilterErrorWriter.write(res, HttpServletResponse.SC_BAD_REQUEST, "bad_request",
          "X-External-Application-Id (or application-id) is required for external clients");
      return;
    }

    final UUID applicationId;
    try {
      applicationId = UUID.fromString(appHeader.trim());
    } catch (IllegalArgumentException e) {
      FilterErrorWriter.write(res, HttpServletResponse.SC_BAD_REQUEST, "bad_request",
          "Invalid X-External-Application-Id / application-id");
      return;
    }

    String method = req.getMethod() == null ? "GET" : req.getMethod().toUpperCase();
    boolean mutating = "POST".equals(method) || "PUT".equals(method)
        || "PATCH".equals(method) || "DELETE".equals(method);
    if (mutating && !scopes.contains(WRITE_SCOPE)) {
      FilterErrorWriter.write(res, HttpServletResponse.SC_FORBIDDEN, "forbidden",
          "insufficient_scope: " + WRITE_SCOPE + " required");
      return;
    }
    if ("GET".equals(method) && !scopes.contains(READ_SCOPE) && !scopes.contains(WRITE_SCOPE)) {
      FilterErrorWriter.write(res, HttpServletResponse.SC_FORBIDDEN, "forbidden",
          "insufficient_scope: " + READ_SCOPE + " required");
      return;
    }

    UUID synthetic = ExternalAuth.SYNTHETIC_NIL;
    TenantContext ctx = new TenantContext(
        synthetic, synthetic, applicationId, applicationId,
        true, true, List.of("EXTERNAL"), scopes, Set.of(), synthetic,
        "external:" + clientId, "external@" + clientId, "UTC", true, clientId);

    List<SimpleGrantedAuthority> authorities = scopes.stream()
        .map(s -> new SimpleGrantedAuthority("SCOPE_" + s))
        .collect(Collectors.toList());
    authorities.add(new SimpleGrantedAuthority("ROLE_EXTERNAL"));

    SecurityContextHolder.getContext().setAuthentication(
        new UsernamePasswordAuthenticationToken(clientId, "external", authorities));
    TenantContext.set(ctx);
    req.setAttribute(ExternalAuth.REQUEST_ATTR, Boolean.TRUE);

    log.info("External context clientId={} appId={} scopes={} path={}",
        clientId, applicationId, scopes, resolvePath(req));

    try {
      chain.doFilter(req, res);
    } finally {
      TenantContext.clear();
      SecurityContextHolder.clearContext();
    }
  }

  private static boolean isPublicPath(String path) {
    return path.equals("/health") || path.startsWith("/health/")
        || path.startsWith("/actuator/")
        || path.equals("/swagger-ui.html") || path.startsWith("/swagger-ui/")
        || path.startsWith("/docs")
        || path.equals("/v3/api-docs") || path.startsWith("/v3/api-docs/");
  }

  private static Set<String> parseScopes(String header) {
    if (header == null || header.isBlank()) return Set.of();
    return Arrays.stream(header.trim().split("[\\s,]+"))
        .map(String::trim).filter(s -> !s.isEmpty())
        .collect(Collectors.toCollection(HashSet::new));
  }

  private static String firstNonBlank(String... values) {
    if (values == null) return null;
    for (String v : values) {
      if (v != null && !v.isBlank()) return v;
    }
    return null;
  }

  private static String resolvePath(HttpServletRequest request) {
    String servletPath = request.getServletPath();
    if (servletPath != null && !servletPath.isEmpty()) return servletPath;
    String uri = request.getRequestURI();
    String context = request.getContextPath();
    if (context != null && !context.isEmpty() && uri.startsWith(context)) {
      return uri.substring(context.length());
    }
    return uri;
  }
}