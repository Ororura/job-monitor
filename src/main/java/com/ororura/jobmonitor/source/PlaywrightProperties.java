package com.ororura.jobmonitor.source;

import java.net.URI;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("app.playwright")
public record PlaywrightProperties(
    boolean enabled,
    boolean headless,
    Duration navigationTimeout,
    Duration operationTimeout,
    int maxPages,
    int maxVacancies,
    URI searchUrl,
    boolean accessConfirmed) {
  public PlaywrightProperties {
    if (navigationTimeout == null
        || navigationTimeout.toMillis() < 1
        || operationTimeout == null
        || operationTimeout.toMillis() < 1) {
      throw new IllegalArgumentException("Playwright timeouts must be positive");
    }
    if (maxPages < 1 || maxVacancies < 1) {
      throw new IllegalArgumentException("Playwright collection limits must be positive");
    }
    if (enabled && (!accessConfirmed || !allowedEndpoint(searchUrl))) {
      throw new IllegalArgumentException(
          "Playwright requires a permitted search URL and confirmed access");
    }
  }

  private static boolean allowedEndpoint(URI uri) {
    if (uri == null
        || uri.getHost() == null
        || uri.getUserInfo() != null
        || uri.getRawQuery() != null
        || uri.getFragment() != null
        || !("https".equals(uri.getScheme()) || "http".equals(uri.getScheme()))) {
      return false;
    }
    String host = uri.getHost().toLowerCase(java.util.Locale.ROOT);
    return !host.equals("hh.ru") && !host.endsWith(".hh.ru");
  }
}
