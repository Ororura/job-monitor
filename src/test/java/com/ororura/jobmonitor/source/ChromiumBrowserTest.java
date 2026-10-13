package com.ororura.jobmonitor.source;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.microsoft.playwright.*;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class ChromiumBrowserTest {
  private final Playwright playwright = mock(Playwright.class);
  private final BrowserType chromium = mock(BrowserType.class);
  private final Browser browser = mock(Browser.class);
  private final BrowserContext context = mock(BrowserContext.class);
  private final Page page = mock(Page.class);

  static PlaywrightProperties properties() {
    return new PlaywrightProperties(
        false, true, Duration.ofSeconds(3), Duration.ofSeconds(1), 5, 100, null, false);
  }

  private ChromiumBrowser configuredBrowser() {
    when(playwright.chromium()).thenReturn(chromium);
    when(chromium.launch(any())).thenReturn(browser);
    when(browser.newContext()).thenReturn(context);
    when(context.newPage()).thenReturn(page);
    return new ChromiumBrowser(properties(), () -> playwright);
  }

  @Test
  void closesAllResourcesInReverseOrderOnTheCallingThread() {
    var infrastructure = configuredBrowser();
    Thread owner = Thread.currentThread();
    List<Thread> threads = new ArrayList<>();
    doAnswer(
            invocation -> {
              threads.add(Thread.currentThread());
              return null;
            })
        .when(page)
        .close();
    doAnswer(
            invocation -> {
              threads.add(Thread.currentThread());
              return null;
            })
        .when(context)
        .close();
    doAnswer(
            invocation -> {
              threads.add(Thread.currentThread());
              return null;
            })
        .when(browser)
        .close();
    doAnswer(
            invocation -> {
              threads.add(Thread.currentThread());
              return null;
            })
        .when(playwright)
        .close();
    assertThat(
            infrastructure.<String>withPage(
                p -> {
                  assertThat(Thread.currentThread()).isSameAs(owner);
                  return "ok";
                }))
        .isEqualTo("ok");
    var order = inOrder(page, context, browser, playwright);
    order.verify(page).close();
    order.verify(context).close();
    order.verify(browser).close();
    order.verify(playwright).close();
    assertThat(threads).containsExactly(owner, owner, owner, owner);
    verify(context).setDefaultTimeout(1000);
    verify(context).setDefaultNavigationTimeout(3000);
    verify(chromium).launch(argThat(options -> options.headless && options.timeout == 3000));
  }

  @Test
  void closesResourcesWhenCollectionFails() {
    var infrastructure = configuredBrowser();
    assertThatThrownBy(
            () ->
                infrastructure.withPage(
                    p -> {
                      throw new BrowserSourceException(BrowserSourceException.Reason.BLOCKED);
                    }))
        .isInstanceOf(BrowserSourceException.class)
        .hasMessageContaining("BLOCKED");
    verify(page).close();
    verify(context).close();
    verify(browser).close();
    verify(playwright).close();
  }

  @Test
  void missingBinariesClosePlaywrightAndProduceSafeLaunchError() {
    var infrastructure = configuredBrowser();
    when(chromium.launch(any()))
        .thenThrow(new PlaywrightException("Executable missing token=secret"));
    assertThatThrownBy(() -> infrastructure.withPage(p -> null))
        .isInstanceOf(BrowserSourceException.class)
        .hasMessage("Browser collection failed: LAUNCH");
    verify(playwright).close();
    verifyNoInteractions(page, context);
  }

  @Test
  void contextCreationFailureClosesBrowserAndPlaywright() {
    var infrastructure = configuredBrowser();
    when(browser.newContext()).thenThrow(new PlaywrightException("private"));
    assertThatThrownBy(() -> infrastructure.withPage(p -> null))
        .isInstanceOf(BrowserSourceException.class);
    verify(browser).close();
    verify(playwright).close();
  }

  @Test
  void driverFailureIsSanitized() {
    var infrastructure =
        new ChromiumBrowser(
            properties(),
            () -> {
              throw new IllegalStateException("secret");
            });
    assertThatThrownBy(() -> infrastructure.withPage(p -> null))
        .hasMessage("Browser collection failed: LAUNCH");
  }

  @Test
  void closesOtherResourcesEvenIfPageCloseFails() {
    var infrastructure = configuredBrowser();
    doThrow(new PlaywrightException("private")).when(page).close();
    assertThatThrownBy(() -> infrastructure.withPage(p -> null))
        .isInstanceOf(BrowserSourceException.class);
    verify(context).close();
    verify(browser).close();
    verify(playwright).close();
  }
}
