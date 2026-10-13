package com.ororura.jobmonitor.source;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@Tag("browser")
class PlaywrightBrowserIntegrationTest {
  private static final String BLOCKED = "BLOCKED";

  private PlaywrightVacancySource source(
      LocalVacancyServer server, int pages, int vacancies, Duration timeout) {
    var properties =
        new PlaywrightProperties(
            true,
            Boolean.parseBoolean(System.getProperty("jobmonitor.browser.headless", "true")),
            timeout,
            Duration.ofSeconds(1),
            pages,
            vacancies,
            server.searchUrl(),
            true);
    return new PlaywrightVacancySource(
        properties, new ChromiumBrowser(properties), new HtmlVacancyParser());
  }

  @Test
  void navigatesRealPagesPreservesExpressionAndDeduplicatesWithoutVisitingDetails()
      throws Exception {
    try (var server = new LocalVacancyServer()) {
      var query = PlaywrightVacancySourceTest.query();
      var vacancies = source(server, 5, 100, Duration.ofSeconds(5)).fetch(query);
      assertThat(vacancies).extracting(v -> v.externalId()).containsExactly("101", "102", "103");
      assertThat(server.requests()).hasSize(2).allMatch(uri -> uri.getPath().equals("/search"));
      for (var request : server.requests()) {
        String expression =
            URLDecoder.decode(request.getRawQuery().split("q=", 2)[1], StandardCharsets.UTF_8);
        assertThat(expression).isEqualTo(query.queryText());
      }
    }
  }

  @Test
  void createsIsolatedContextForEveryCollection() throws Exception {
    try (var server = new LocalVacancyServer()) {
      server.fixture("empty");
      var source = source(server, 5, 100, Duration.ofSeconds(5));
      source.fetch(PlaywrightVacancySourceTest.query());
      source.fetch(PlaywrightVacancySourceTest.query());
      assertThat(server.requests()).hasSize(2);
      assertThat(server.cookieCount()).isZero();
    }
  }

  @Test
  void waitsForAsynchronouslyInsertedResultsThroughLocator() throws Exception {
    try (var server = new LocalVacancyServer()) {
      server.fixture("page2");
      server.asynchronousResults();
      assertThat(
              source(server, 5, 100, Duration.ofSeconds(5))
                  .fetch(PlaywrightVacancySourceTest.query()))
          .hasSize(2);
    }
  }

  @Test
  void honorsPageLimitWithRealChromium() throws Exception {
    try (var server = new LocalVacancyServer()) {
      assertThat(
              source(server, 1, 100, Duration.ofSeconds(5))
                  .fetch(PlaywrightVacancySourceTest.query()))
          .hasSize(2);
      assertThat(server.requests()).hasSize(1);
    }
  }

  @Test
  void honorsVacancyLimitWithRealChromium() throws Exception {
    try (var server = new LocalVacancyServer()) {
      assertThat(
              source(server, 5, 1, Duration.ofSeconds(5))
                  .fetch(PlaywrightVacancySourceTest.query()))
          .hasSize(1);
      assertThat(server.requests()).hasSize(1);
    }
  }

  @ParameterizedTest
  @ValueSource(ints = {403, 429})
  void stopsOnDeniedHttpResponses(int status) throws Exception {
    try (var server = new LocalVacancyServer()) {
      server.status(status);
      assertThatThrownBy(
              () ->
                  source(server, 5, 100, Duration.ofSeconds(5))
                      .fetch(PlaywrightVacancySourceTest.query()))
          .hasMessageContaining(BLOCKED);
      assertThat(server.requests()).hasSize(1);
    }
  }

  @Test
  void refusesRedirectsBeforeContactingTheirDestination() throws Exception {
    try (var server = new LocalVacancyServer();
        var destination = new LocalVacancyServer()) {
      server.redirect(destination.searchUrl());
      assertThatThrownBy(
              () ->
                  source(server, 5, 100, Duration.ofSeconds(5))
                      .fetch(PlaywrightVacancySourceTest.query()))
          .hasMessageContaining(BLOCKED);
      assertThat(destination.requests()).isEmpty();
    }
  }

  @Test
  void blocksPopupNavigationBeforeContactingAnotherOrigin() throws Exception {
    try (var server = new LocalVacancyServer();
        var destination = new LocalVacancyServer()) {
      server.appendHtml("<script>window.open('" + destination.searchUrl() + "')</script>");
      assertThatThrownBy(
              () ->
                  source(server, 5, 100, Duration.ofSeconds(5))
                      .fetch(PlaywrightVacancySourceTest.query()))
          .hasMessageContaining(BLOCKED);
      assertThat(destination.requests()).isEmpty();
    }
  }

  @Test
  void detectsCaptcha() throws Exception {
    try (var server = new LocalVacancyServer()) {
      server.fixture("blocked");
      assertThatThrownBy(
              () ->
                  source(server, 5, 100, Duration.ofSeconds(5))
                      .fetch(PlaywrightVacancySourceTest.query()))
          .hasMessageContaining(BLOCKED);
    }
  }

  @Test
  void changedMarkupIsFailureInsteadOfEmptySuccess() throws Exception {
    try (var server = new LocalVacancyServer()) {
      server.fixture("changed");
      assertThatThrownBy(
              () ->
                  source(server, 5, 100, Duration.ofSeconds(5))
                      .fetch(PlaywrightVacancySourceTest.query()))
          .hasMessageContaining("STRUCTURE");
    }
  }

  @Test
  void loadingPageTimesOutWaitingForResults() throws Exception {
    try (var server = new LocalVacancyServer()) {
      server.fixture("loading");
      assertThatThrownBy(
              () ->
                  source(server, 5, 100, Duration.ofSeconds(5))
                      .fetch(PlaywrightVacancySourceTest.query()))
          .hasMessageContaining("TIMEOUT");
    }
  }

  @Test
  void slowNavigationTimesOutAndClosesResources() throws Exception {
    try (var server = new LocalVacancyServer();
        var executor = Executors.newSingleThreadExecutor()) {
      server.delayResponse();
      var source = source(server, 5, 100, Duration.ofMillis(300));
      var result = executor.submit(() -> source.fetch(PlaywrightVacancySourceTest.query()));
      assertThat(server.awaitRequest()).isTrue();
      assertThatThrownBy(() -> result.get(10, TimeUnit.SECONDS))
          .hasCauseInstanceOf(BrowserSourceException.class)
          .cause()
          .hasMessageContaining("TIMEOUT");
      server.releaseResponse();
    }
  }

  @Test
  void connectionFailureIsSanitized() throws Exception {
    try (var server = new LocalVacancyServer()) {
      var source = source(server, 5, 100, Duration.ofSeconds(5));
      server.close();
      assertThatThrownBy(() -> source.fetch(PlaywrightVacancySourceTest.query()))
          .hasMessageContaining("NAVIGATION");
    }
  }
}
