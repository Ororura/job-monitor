package com.ororura.jobmonitor.source;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("browser")
class HhBrowserIntegrationTest {
  private static final String RESULTS = "hh-results";

  private PlaywrightVacancySource source(LocalVacancyServer server, int pages, int vacancies) {
    var endpoint = server.searchUrl().resolve("/search/vacancy?area=2&order_by=publication_time");
    var properties =
        new PlaywrightProperties(
            true,
            true,
            Duration.ofSeconds(5),
            Duration.ofSeconds(2),
            pages,
            vacancies,
            endpoint,
            true,
            PlaywrightProperties.HtmlFormat.HH);
    return new PlaywrightVacancySource(
        properties,
        new ChromiumBrowser(properties),
        new HtmlVacancyParser(),
        new HhVacancyParser());
  }

  @Test
  void collectsHhFixturePagesKeepsFiltersAndExpressionAndNeverVisitsAds() throws Exception {
    try (var server = new LocalVacancyServer()) {
      server.fixture(RESULTS);
      var query = PlaywrightVacancySourceTest.query();
      var vacancies = source(server, 100, 5000).fetch(query);
      assertThat(vacancies).extracting(v -> v.externalId()).containsExactly("101", "102", "103");
      assertThat(vacancies).allMatch(v -> v.source().equals("HH"));
      assertThat(server.requests()).hasSize(2);
      for (URI request : server.requests()) {
        assertThat(request.getPath()).isEqualTo("/search/vacancy");
        assertThat(request.getRawQuery()).contains("area=2", "order_by=publication_time");
        String expression =
            URLDecoder.decode(request.getRawQuery().split("text=", 2)[1], StandardCharsets.UTF_8);
        assertThat(expression).isEqualTo(query.queryText());
      }
    }
  }

  @Test
  void respectsOnePageAndVacancyLimits() throws Exception {
    try (var server = new LocalVacancyServer()) {
      server.fixture(RESULTS);
      assertThat(source(server, 1, 5000).fetch(PlaywrightVacancySourceTest.query())).hasSize(2);
      assertThat(source(server, 100, 1).fetch(PlaywrightVacancySourceTest.query())).hasSize(1);
      assertThat(server.requests()).hasSize(2);
    }
  }

  @Test
  void recognizesEmptyHhPageWithoutTreatingUnknownStructureAsEmpty() throws Exception {
    try (var server = new LocalVacancyServer()) {
      server.fixture("hh-empty");
      assertThat(source(server, 100, 5000).fetch(PlaywrightVacancySourceTest.query())).isEmpty();
      server.fixture("changed");
      assertThatThrownBy(() -> source(server, 100, 5000).fetch(PlaywrightVacancySourceTest.query()))
          .hasMessageContaining("TIMEOUT");
    }
  }

  @Test
  void skipsOptionalForeignFramesWithoutContactingThemOrFailingSearch() throws Exception {
    try (var server = new LocalVacancyServer();
        var frameServer = new LocalVacancyServer()) {
      server.fixture(RESULTS);
      server.appendHtml("<iframe src='" + frameServer.searchUrl() + "'></iframe>");
      assertThat(source(server, 1, 5000).fetch(PlaywrightVacancySourceTest.query())).hasSize(2);
      assertThat(frameServer.requests()).isEmpty();
    }
  }

  @Test
  void deniedHhFixtureRunStopsImmediately() throws Exception {
    try (var server = new LocalVacancyServer()) {
      server.fixture(RESULTS);
      server.status(429);
      assertThatThrownBy(() -> source(server, 100, 5000).fetch(PlaywrightVacancySourceTest.query()))
          .hasMessageContaining("BLOCKED");
      assertThat(server.requests()).hasSize(1);
    }
  }
}
