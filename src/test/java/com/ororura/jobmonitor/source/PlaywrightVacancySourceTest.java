package com.ororura.jobmonitor.source;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.PlaywrightException;
import com.microsoft.playwright.Response;
import com.microsoft.playwright.TimeoutError;
import com.ororura.jobmonitor.search.SearchQuery;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;

class PlaywrightVacancySourceTest {
  private static final String RESULTS = "results";
  private static final String URL = "http://127.0.0.1:8081/search";
  private static final String EXPRESSION =
      "  (Java AND \"Spring Boot\") OR (Kotlin AND NOT \"senior\") + C# & Москва  ";
  private final ChromiumBrowser browser = mock(ChromiumBrowser.class);
  private final Page page = mock(Page.class);
  private final BrowserContext context = mock(BrowserContext.class);
  private final Locator locator = mock(Locator.class);
  private final Response response = mock(Response.class);

  static SearchQuery query() {
    return new SearchQuery(
        UUID.randomUUID(),
        "Browser",
        EXPRESSION,
        10,
        true,
        Instant.EPOCH,
        Instant.EPOCH,
        null,
        "PLAYWRIGHT");
  }

  private PlaywrightVacancySource configured(int maxPages, int maxVacancies) {
    when(page.context()).thenReturn(context);
    var properties =
        new PlaywrightProperties(
            true,
            true,
            Duration.ofSeconds(2),
            Duration.ofSeconds(1),
            maxPages,
            maxVacancies,
            URI.create(URL),
            true);
    when(browser.withPage(any()))
        .thenAnswer(
            invocation -> {
              Function<Page, ?> action = invocation.getArgument(0);
              return action.apply(page);
            });
    when(page.navigate(anyString(), any())).thenReturn(response);
    when(response.status()).thenReturn(200);
    when(page.url()).thenReturn(URL);
    when(page.locator(anyString())).thenReturn(locator);
    when(locator.first()).thenReturn(locator);
    return new PlaywrightVacancySource(properties, browser, new HtmlVacancyParser());
  }

  @Test
  void hhQueryUrlUsesTextAndPreservesConfiguredFiltersAndRawExpression() {
    var uri =
        PlaywrightVacancySource.queryUrl(
            URI.create(
                "https://spb.hh.ru/search/vacancy?text=old&area=2&order_by=publication_time&page=1"),
            EXPRESSION);
    assertThat(uri.getRawQuery())
        .contains("area=2", "order_by=publication_time", "page=1")
        .doesNotContain("text=old", "q=");
    assertThat(URLDecoder.decode(uri.getRawQuery().split("text=", 2)[1], StandardCharsets.UTF_8))
        .isEqualTo(EXPRESSION);
  }

  @Test
  void followsPaginationDeduplicatesAndPreservesExpressionOnEveryPage() throws Exception {
    var source = configured(5, 100);
    when(page.content())
        .thenReturn(HtmlVacancyParserTest.fixture(RESULTS), HtmlVacancyParserTest.fixture("page2"));
    var vacancies = source.fetch(query());
    assertThat(vacancies).extracting(v -> v.externalId()).containsExactly("101", "102", "103");
    var urls = ArgumentCaptor.forClass(String.class);
    verify(page, times(2)).navigate(urls.capture(), any());
    for (String url : urls.getAllValues()) {
      assertThat(
              URLDecoder.decode(
                  URI.create(url).getRawQuery().split("q=", 2)[1], StandardCharsets.UTF_8))
          .isEqualTo(EXPRESSION);
    }
    assertThat(urls.getAllValues().get(1)).contains("page=2");
    verify(locator, times(2)).waitFor(any());
  }

  @Test
  void enforcesPageLimit() throws Exception {
    var source = configured(1, 100);
    when(page.content()).thenReturn(HtmlVacancyParserTest.fixture(RESULTS));
    assertThat(source.fetch(query())).hasSize(2);
    verify(page).navigate(anyString(), any());
  }

  @Test
  void enforcesVacancyLimitWithoutLoadingNextPage() throws Exception {
    var source = configured(5, 1);
    when(page.content()).thenReturn(HtmlVacancyParserTest.fixture(RESULTS));
    assertThat(source.fetch(query())).hasSize(1);
    verify(page).navigate(anyString(), any());
  }

  @Test
  void stopsAtExplicitEmptyPage() throws Exception {
    var source = configured(5, 100);
    when(page.content()).thenReturn(HtmlVacancyParserTest.fixture("empty"));
    assertThat(source.fetch(query())).isEmpty();
    verify(page).navigate(anyString(), any());
  }

  @Test
  void stopsPaginationCycles() throws Exception {
    var source = configured(5, 100);
    when(page.content())
        .thenReturn(HtmlVacancyParserTest.fixture(RESULTS).replace("/search?page=2", "/search"));
    assertThat(source.fetch(query())).hasSize(2);
    verify(page).navigate(anyString(), any());
  }

  @Test
  void refusesNextPageOutsideConfiguredEndpoint() throws Exception {
    var source = configured(5, 100);
    when(page.content())
        .thenReturn(
            HtmlVacancyParserTest.fixture(RESULTS)
                .replace("/search?page=2", "https://hh.ru/search"));
    assertThatThrownBy(() -> source.fetch(query())).hasMessageContaining("BLOCKED");
    verify(page).navigate(anyString(), any());
  }

  @ParameterizedTest
  @ValueSource(ints = {403, 429})
  void stopsOnAccessDenial(int status) {
    var source = configured(5, 100);
    when(response.status()).thenReturn(status);
    assertThatThrownBy(() -> source.fetch(query())).hasMessageContaining("BLOCKED");
    verify(page, never()).content();
  }

  @Test
  void sanitizesNavigationFailure() {
    var source = configured(5, 100);
    when(page.navigate(anyString(), any()))
        .thenThrow(new PlaywrightException("url contains token=secret"));
    assertThatThrownBy(() -> source.fetch(query()))
        .hasMessage("Browser collection failed: NAVIGATION");
  }

  @Test
  void distinguishesNavigationTimeout() {
    var source = configured(5, 100);
    when(page.navigate(anyString(), any())).thenThrow(new TimeoutError("private"));
    assertThatThrownBy(() -> source.fetch(query()))
        .hasMessage("Browser collection failed: TIMEOUT");
  }

  @Test
  void distinguishesResultLocatorTimeout() {
    var source = configured(5, 100);
    doThrow(new TimeoutError("private")).when(locator).waitFor(any());
    assertThatThrownBy(() -> source.fetch(query()))
        .hasMessage("Browser collection failed: TIMEOUT");
  }

  @Test
  void shutdownStopsQueuedJobsWithoutCreatingBrowserResources() {
    var source = configured(5, 100);
    source.onApplicationEvent(
        new org.springframework.context.event.ContextClosedEvent(
            mock(org.springframework.context.ApplicationContext.class)));
    assertThatThrownBy(() -> source.fetch(query())).hasMessageContaining("SHUTDOWN");
    verify(browser, never()).withPage(any());
  }

  @Test
  void shutdownDuringCollectionPreventsNavigationToAnotherPage() throws Exception {
    var source = configured(5, 100);
    when(page.content())
        .thenAnswer(
            invocation -> {
              source.onApplicationEvent(
                  new org.springframework.context.event.ContextClosedEvent(
                      mock(org.springframework.context.ApplicationContext.class)));
              return HtmlVacancyParserTest.fixture(RESULTS);
            });
    assertThatThrownBy(() -> source.fetch(query())).hasMessageContaining("SHUTDOWN");
    verify(page).navigate(anyString(), any());
  }

  @Test
  void disabledSourceDoesNotCreateBrowser() {
    var source =
        new PlaywrightVacancySource(
            ChromiumBrowserTest.properties(), browser, new HtmlVacancyParser());
    assertThatThrownBy(() -> source.fetch(query())).hasMessageContaining("DISABLED");
    verifyNoInteractions(browser);
  }

  @Test
  void replacesSourceSuppliedQueryWithoutChangingOtherParameters() {
    var url =
        PlaywrightVacancySource.queryUrl(
            URI.create(URL + "?page=2&q=rewritten&sort=date#fragment"), EXPRESSION);
    assertThat(url.getRawQuery()).startsWith("page=2&sort=date&q=");
    assertThat(URLDecoder.decode(url.getRawQuery().split("q=", 2)[1], StandardCharsets.UTF_8))
        .isEqualTo(EXPRESSION);
    assertThat(url.getFragment()).isNull();
  }
}
