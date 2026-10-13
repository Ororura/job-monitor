package com.ororura.jobmonitor.source;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.PlaywrightException;
import com.microsoft.playwright.options.ServiceWorkerPolicy;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class ChromiumBrowser {
  private final PlaywrightProperties properties;
  private final Supplier<Playwright> playwrightFactory;

  @Autowired
  public ChromiumBrowser(PlaywrightProperties properties) {
    this(
        properties,
        () ->
            Playwright.create(
                new Playwright.CreateOptions()
                    .setEnv(Map.of("PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD", "1"))));
  }

  ChromiumBrowser(PlaywrightProperties properties, Supplier<Playwright> playwrightFactory) {
    this.properties = properties;
    this.playwrightFactory = playwrightFactory;
  }

  public <T> T withPage(Function<Page, T> action) {
    // No browser state escapes this invocation: creation, use and teardown share the worker thread.
    try (Playwright playwright = createPlaywright();
        Browser browser = launch(playwright);
        BrowserContext context =
            browser.newContext(
                new Browser.NewContextOptions().setServiceWorkers(ServiceWorkerPolicy.BLOCK));
        Page page = context.newPage()) {
      context.setDefaultNavigationTimeout(properties.navigationTimeout().toMillis());
      context.setDefaultTimeout(properties.operationTimeout().toMillis());
      return action.apply(page);
    } catch (PlaywrightException exception) {
      throw new BrowserSourceException(BrowserSourceException.Reason.NAVIGATION);
    }
  }

  private Playwright createPlaywright() {
    try {
      return playwrightFactory.get();
    } catch (PlaywrightException | IllegalStateException exception) {
      throw new BrowserSourceException(BrowserSourceException.Reason.LAUNCH);
    }
  }

  private Browser launch(Playwright playwright) {
    try {
      return playwright
          .chromium()
          .launch(
              new BrowserType.LaunchOptions()
                  .setHeadless(properties.headless())
                  .setTimeout(properties.navigationTimeout().toMillis()));
    } catch (PlaywrightException exception) {
      throw new BrowserSourceException(BrowserSourceException.Reason.LAUNCH);
    }
  }
}
