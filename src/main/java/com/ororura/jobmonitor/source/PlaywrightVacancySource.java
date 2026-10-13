package com.ororura.jobmonitor.source;

import com.microsoft.playwright.APIResponse;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.PlaywrightException;
import com.microsoft.playwright.Route;
import com.microsoft.playwright.TimeoutError;
import com.microsoft.playwright.options.WaitForSelectorState;
import com.microsoft.playwright.options.WaitUntilState;
import com.ororura.jobmonitor.search.SearchQuery;
import com.ororura.jobmonitor.vacancy.VacancyData;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationListener;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.stereotype.Component;

@Component
public class PlaywrightVacancySource
    implements VacancySource, ApplicationListener<ContextClosedEvent> {
  private static final Logger LOGGER = LoggerFactory.getLogger(PlaywrightVacancySource.class);
  private final PlaywrightProperties properties;
  private final ChromiumBrowser browser;
  private final HtmlVacancyParser parser;
  private final AtomicBoolean stopping = new AtomicBoolean();

  public PlaywrightVacancySource(
      PlaywrightProperties properties, ChromiumBrowser browser, HtmlVacancyParser parser) {
    this.properties = properties;
    this.browser = browser;
    this.parser = parser;
  }

  @Override
  public String source() {
    return "PLAYWRIGHT";
  }

  @Override
  public List<VacancyData> fetch(SearchQuery query) {
    requireRunning();
    if (!properties.enabled()) {
      throw new BrowserSourceException(BrowserSourceException.Reason.DISABLED);
    }
    try {
      return browser.withPage(page -> collect(page, query.queryText()));
    } catch (BrowserSourceException exception) {
      LOGGER.warn("Browser collection failed ({})", exception.reason());
      throw exception;
    }
  }

  private List<VacancyData> collect(Page page, String expression) {
    var accessFailure = new AtomicReference<BrowserSourceException.Reason>();
    page.context().route("**/*", route -> permittedRequest(route, accessFailure));
    var unique = new LinkedHashMap<String, VacancyData>();
    Set<URI> visited = new HashSet<>();
    URI next = queryUrl(properties.searchUrl(), expression);
    for (int number = 0; number < properties.maxPages(); number++) {
      requireRunning();
      requireSearchPage(next);
      if (!visited.add(next)) {
        break;
      }
      navigate(page, next, accessFailure);
      var parsed = parser.parse(page.content(), page.url(), properties.maxVacancies());
      checkAccess(accessFailure);
      if (parsed.skippedCards() > 0) {
        LOGGER.warn("Skipped {} malformed vacancy cards", parsed.skippedCards());
      }
      for (VacancyData vacancy : parsed.vacancies()) {
        unique.putIfAbsent(vacancy.externalId(), vacancy);
        if (unique.size() >= properties.maxVacancies()) {
          return List.copyOf(unique.values());
        }
      }
      if (parsed.nextUrl() == null) {
        break;
      }
      next = queryUrl(URI.create(parsed.nextUrl()), expression);
    }
    return List.copyOf(unique.values());
  }

  private void navigate(
      Page page, URI url, AtomicReference<BrowserSourceException.Reason> accessFailure) {
    try {
      var response =
          page.navigate(
              url.toString(),
              new Page.NavigateOptions().setWaitUntil(WaitUntilState.DOMCONTENTLOADED));
      checkAccess(accessFailure);
      if (response == null) {
        throw new BrowserSourceException(BrowserSourceException.Reason.NAVIGATION);
      }
      int status = response.status();
      if (status == 403 || status == 429) {
        throw new BrowserSourceException(BrowserSourceException.Reason.BLOCKED);
      }
      if (status < 200 || status >= 300) {
        throw new BrowserSourceException(BrowserSourceException.Reason.NAVIGATION);
      }
      page.locator(HtmlVacancyParser.READY)
          .first()
          .waitFor(new Locator.WaitForOptions().setState(WaitForSelectorState.ATTACHED));
      checkAccess(accessFailure);
    } catch (TimeoutError exception) {
      checkAccess(accessFailure);
      throw new BrowserSourceException(BrowserSourceException.Reason.TIMEOUT);
    } catch (PlaywrightException exception) {
      checkAccess(accessFailure);
      throw new BrowserSourceException(BrowserSourceException.Reason.NAVIGATION);
    }
  }

  private void permittedRequest(
      Route route, AtomicReference<BrowserSourceException.Reason> accessFailure) {
    URI target = URI.create(route.request().url());
    if (!sameOrigin(properties.searchUrl(), target)) {
      if (route.request().isNavigationRequest()) {
        accessFailure.compareAndSet(null, BrowserSourceException.Reason.BLOCKED);
      }
      route.abort();
      return;
    }
    // Routing alone does not intercept redirect destinations. Fetch without following redirects,
    // then fulfill the original request, so no unapproved origin can be contacted via a 3xx.
    try {
      APIResponse response =
          route.fetch(
              new Route.FetchOptions()
                  .setMaxRedirects(0)
                  .setTimeout(properties.navigationTimeout().toMillis()));
      try {
        int status = response.status();
        if (status == 403 || status == 429 || (status >= 300 && status < 400)) {
          accessFailure.compareAndSet(null, BrowserSourceException.Reason.BLOCKED);
          route.abort();
        } else {
          route.fulfill(new Route.FulfillOptions().setResponse(response));
        }
      } finally {
        response.dispose();
      }
    } catch (TimeoutError exception) {
      accessFailure.compareAndSet(null, BrowserSourceException.Reason.TIMEOUT);
      route.abort();
    } catch (PlaywrightException exception) {
      accessFailure.compareAndSet(null, BrowserSourceException.Reason.NAVIGATION);
      route.abort();
    }
  }

  private void requireSearchPage(URI uri) {
    if (!sameOrigin(properties.searchUrl(), uri)
        || uri.getUserInfo() != null
        || !properties.searchUrl().getPath().equals(uri.getPath())) {
      throw new BrowserSourceException(BrowserSourceException.Reason.BLOCKED);
    }
  }

  private static boolean sameOrigin(URI base, URI target) {
    return base.getScheme().equalsIgnoreCase(target.getScheme())
        && base.getHost().equalsIgnoreCase(target.getHost())
        && port(base) == port(target);
  }

  private static int port(URI uri) {
    return uri.getPort() >= 0
        ? uri.getPort()
        : ("https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80);
  }

  static URI queryUrl(URI target, String expression) {
    StringBuilder parameters = new StringBuilder();
    String existing = target.getRawQuery();
    if (existing != null) {
      for (String parameter : existing.split("&")) {
        String key = parameter.split("=", 2)[0];
        if (!"q".equals(URLDecoder.decode(key, StandardCharsets.UTF_8))) {
          parameters.append(parameter).append('&');
        }
      }
    }
    parameters.append("q=").append(URLEncoder.encode(expression, StandardCharsets.UTF_8));
    return URI.create(
        target.getScheme()
            + "://"
            + target.getRawAuthority()
            + target.getRawPath()
            + "?"
            + parameters);
  }

  private static void checkAccess(AtomicReference<BrowserSourceException.Reason> failure) {
    if (failure.get() != null) {
      throw new BrowserSourceException(failure.get());
    }
  }

  @Override
  public void onApplicationEvent(ContextClosedEvent event) {
    // Shutdown only signals the worker. Playwright teardown remains on its owning thread.
    stopping.set(true);
  }

  private void requireRunning() {
    if (stopping.get() || Thread.currentThread().isInterrupted()) {
      throw new BrowserSourceException(BrowserSourceException.Reason.SHUTDOWN);
    }
  }
}
