package com.ororura.jobmonitor.source;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class HtmlVacancyParserTest {
  private static final String RESULTS = "results";
  private static final String URL = "http://127.0.0.1:8081/search";
  private final HtmlVacancyParser parser = new HtmlVacancyParser();

  static String fixture(String name) throws IOException {
    try (var stream =
        HtmlVacancyParserTest.class.getResourceAsStream("/fixtures/" + name + ".html")) {
      if (stream == null) {
        throw new IOException("Fixture missing");
      }
      return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
    }
  }

  @Test
  void extractsFieldsWithPreciseDecimalsAndCanonicalUrls() throws IOException {
    var parsed = parser.parse(fixture(RESULTS), URL, 100);
    assertThat(parsed.vacancies()).hasSize(2);
    var first = parsed.vacancies().getFirst();
    assertThat(first.source()).isEqualTo("PLAYWRIGHT");
    assertThat(first.externalId()).isEqualTo("101");
    assertThat(first.title()).isEqualTo("Java & Kotlin developer");
    assertThat(first.employerName()).isEqualTo("Example employer");
    assertThat(first.area()).isEqualTo("Remote");
    assertThat(first.salaryFrom()).isEqualTo(new BigDecimal("100000.123456789"));
    assertThat(first.salaryTo()).isEqualTo(new BigDecimal("200000.987654321"));
    assertThat(first.currency()).isEqualTo("RUB");
    assertThat(first.url()).isEqualTo("http://127.0.0.1:8081/vacancies/101");
    assertThat(first.publishedAt()).isEqualTo(Instant.parse("2026-10-13T09:30:00Z"));
    assertThat(parsed.nextUrl()).isEqualTo(URL + "?page=2");
  }

  @Test
  void canonicalUrlPreservesSemanticQueryParameters() throws IOException {
    var html =
        fixture(RESULTS)
            .replace(
                "/vacancies/101?tracking=x#details",
                "/job?id=101&amp;utm_source=fixture&amp;tracking=x#details");
    assertThat(parser.parse(html, URL, 100).vacancies().getFirst().url())
        .isEqualTo("http://127.0.0.1:8081/job?id=101");
  }

  @Test
  void unknownOptionalFieldsStayNullAndIdentityFallsBackToUrl() throws IOException {
    var second = parser.parse(fixture(RESULTS), URL, 100).vacancies().get(1);
    assertThat(second.externalId()).isEqualTo("102");
    assertThat(second.salaryFrom()).isNull();
    assertThat(second.salaryTo()).isNull();
    assertThat(second.currency()).isNull();
    assertThat(second.employerName()).isNull();
    assertThat(second.area()).isNull();
    assertThat(second.publishedAt()).isNull();
  }

  @ParameterizedTest
  @ValueSource(strings = {"RUB", "USD", "EUR", "GBP"})
  void supportsSuppliedCurrenciesWithoutGuessing(String currency) throws IOException {
    var first =
        parser.parse(fixture(RESULTS).replace("RUB", currency), URL, 1).vacancies().getFirst();
    assertThat(first.currency()).isEqualTo(currency);
  }

  @Test
  void acceptsExplicitEmptyPage() throws IOException {
    var parsed = parser.parse(fixture("empty"), URL, 100);
    assertThat(parsed.vacancies()).isEmpty();
    assertThat(parsed.nextUrl()).isNull();
  }

  @Test
  void skipsBrokenCardsAndUnknownPublicationDate() throws IOException {
    var parsed = parser.parse(fixture("malformed"), URL, 100);
    assertThat(parsed.vacancies()).extracting(v -> v.externalId()).containsExactly("104");
    assertThat(parsed.vacancies().getFirst().publishedAt()).isNull();
    assertThat(parsed.skippedCards()).isEqualTo(4);
  }

  @ParameterizedTest
  @ValueSource(strings = {"changed", "loading"})
  void rejectsUnrecognizedStructure(String name) throws IOException {
    assertThatThrownBy(() -> parser.parse(fixture(name), URL, 100))
        .hasMessageContaining("STRUCTURE");
  }

  @Test
  void rejectsPageWhereAllCardsAreBroken() throws IOException {
    assertThatThrownBy(
            () ->
                parser.parse(
                    fixture("malformed").replace("data-job-title", "data-old-title"), URL, 100))
        .hasMessageContaining("STRUCTURE");
  }

  @Test
  void stopsOnCaptcha() throws IOException {
    assertThatThrownBy(() -> parser.parse(fixture("blocked"), URL, 100))
        .hasMessageContaining("BLOCKED");
  }

  @Test
  void limitsParsedResultsAndRecognizesLastPage() throws IOException {
    assertThat(parser.parse(fixture(RESULTS), URL, 1).vacancies()).hasSize(1);
    assertThat(parser.parse(fixture("page2"), URL, 100).nextUrl()).isNull();
  }
}
