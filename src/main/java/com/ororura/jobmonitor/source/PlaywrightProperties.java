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
    boolean accessConfirmed,
    HtmlFormat format) {
  public enum HtmlFormat {
    FIXTURE,
    HH
  }

  public PlaywrightProperties(
      boolean enabled,
      boolean headless,
      Duration navigationTimeout,
      Duration operationTimeout,
      int maxPages,
      int maxVacancies,
      URI searchUrl,
      boolean accessConfirmed) {
    this(
        enabled,
        headless,
        navigationTimeout,
        operationTimeout,
        maxPages,
        maxVacancies,
        searchUrl,
        accessConfirmed,
        null);
  }

  @org.springframework.boot.context.properties.bind.ConstructorBinding
  public PlaywrightProperties {
    format = format == null ? (isHhHost(searchUrl) ? HtmlFormat.HH : HtmlFormat.FIXTURE) : format;
    if (navigationTimeout == null
        || navigationTimeout.toMillis() < 1
        || operationTimeout == null
        || operationTimeout.toMillis() < 1) {
      throw new IllegalArgumentException("Playwright timeouts must be positive");
    }
    if (maxPages < 1 || maxVacancies < 1) {
      throw new IllegalArgumentException("Playwright collection limits must be positive");
    }
    if (format == HtmlFormat.HH && maxPages > 100) {
      throw new IllegalArgumentException("HH collection is limited to 100 pages per run");
    }
    if (enabled && (!accessConfirmed || !allowedEndpoint(searchUrl, format))) {
      throw new IllegalArgumentException(
          "Playwright requires a permitted search URL and confirmed access");
    }
  }

  static boolean isHhHost(URI uri) {
    if (uri == null || uri.getHost() == null) {
      return false;
    }
    String host = uri.getHost().toLowerCase(java.util.Locale.ROOT);
    return host.equals("hh.ru") || host.endsWith(".hh.ru");
  }

  private static boolean allowedEndpoint(URI uri, HtmlFormat format) {
    if (uri == null
        || uri.getHost() == null
        || uri.getUserInfo() != null
        || (uri.getRawQuery() != null && format != HtmlFormat.HH)
        || uri.getFragment() != null
        || !("https".equals(uri.getScheme()) || "http".equals(uri.getScheme()))) {
      return false;
    }
    String host = uri.getHost().toLowerCase(java.util.Locale.ROOT);
    if (isHhHost(uri)) {
      return format == HtmlFormat.HH
          && "https".equals(uri.getScheme())
          && (host.equals("hh.ru") || host.equals("spb.hh.ru"))
          && "/search/vacancy".equals(uri.getPath())
          && (uri.getPort() == -1 || uri.getPort() == 443);
    }
    return true;
  }
}
