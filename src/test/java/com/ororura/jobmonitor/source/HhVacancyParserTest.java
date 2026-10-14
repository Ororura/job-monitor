package com.ororura.jobmonitor.source;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class HhVacancyParserTest {
  private static final String URL = "https://spb.hh.ru/search/vacancy?area=2";
  private static final String RESULTS = "hh-results";
  private final HhVacancyParser parser = new HhVacancyParser();

  @Test
  void extractsActualHhMarkupCanonicalIdentityAndPreciseSalary() throws Exception {
    var parsed = parser.parse(HtmlVacancyParserTest.fixture(RESULTS), URL, 100);
    assertThat(parsed.vacancies()).hasSize(2);
    assertThat(parsed.skippedCards()).isZero();
    var first = parsed.vacancies().getFirst();
    assertThat(first.source()).isEqualTo("HH");
    assertThat(first.externalId()).isEqualTo("101");
    assertThat(first.url()).isEqualTo("https://hh.ru/vacancy/101");
    assertThat(first.title()).isEqualTo("Java & Kotlin developer");
    assertThat(first.employerName()).isEqualTo("Example employer");
    assertThat(first.area()).isEqualTo("Санкт-Петербург");
    assertThat(first.salaryFrom()).isEqualTo(new BigDecimal("100000.123456789"));
    assertThat(first.salaryTo()).isEqualTo(new BigDecimal("200000.987654321"));
    assertThat(first.currency()).isEqualTo("RUB");
    assertThat(first.publishedAt()).isEqualTo(Instant.parse("2026-10-13T09:30:00Z"));
    assertThat(parsed.nextUrl()).contains("page=1", "area=2");
  }

  @Test
  void extractsSponsoredIdentityWithoutFollowingAdOrResponseLink() throws Exception {
    var sponsored =
        parser.parse(HtmlVacancyParserTest.fixture(RESULTS), URL, 100).vacancies().get(1);
    assertThat(sponsored.externalId()).isEqualTo("102");
    assertThat(sponsored.url()).isEqualTo("https://hh.ru/vacancy/102");
    assertThat(sponsored.salaryFrom()).isEqualTo(new BigDecimal("2500.125"));
    assertThat(sponsored.salaryTo()).isNull();
    assertThat(sponsored.currency()).isEqualTo("USD");
    assertThat(sponsored.employerName()).isNull();
    assertThat(sponsored.publishedAt()).isNull();
  }

  @Test
  void acceptsExplicitEmptyResultAndLastPage() throws Exception {
    assertThat(parser.parse(HtmlVacancyParserTest.fixture("hh-empty"), URL, 100).vacancies())
        .isEmpty();
    var last = parser.parse(HtmlVacancyParserTest.fixture("hh-page2"), URL, 100);
    assertThat(last.nextUrl()).isNull();
    assertThat(last.vacancies().get(1).salaryFrom()).isNull();
    assertThat(last.vacancies().get(1).salaryTo()).isEqualTo(new BigDecimal("80000"));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "<form action='/captcha'></form>",
        "<div data-qa='captcha-form'></div>",
        "<iframe src='https://example.invalid/captcha'></iframe>"
      })
  void stopsOnCaptcha(String html) {
    assertThatThrownBy(() -> parser.parse(html, URL, 100)).hasMessageContaining("BLOCKED");
  }

  @Test
  void unknownOrContradictoryMarkupFailsRatherThanReportingEmpty() throws Exception {
    assertThatThrownBy(
            () -> parser.parse("<section data-qa='vacancy-serp__results'></section>", URL, 100))
        .hasMessageContaining("STRUCTURE");
    assertThatThrownBy(
            () ->
                parser.parse(
                    HtmlVacancyParserTest.fixture(RESULTS)
                        + "<div data-qa='empty-vacancy-search-block'></div>",
                    URL,
                    100))
        .hasMessageContaining("STRUCTURE");
  }

  @Test
  void skipsInvalidCardsAndDoesNotInventPublicationTime() throws Exception {
    var html =
        HtmlVacancyParserTest.fixture(RESULTS)
            .replace("https://spb.hh.ru/vacancy/101", "https://outside.invalid/vacancy/101");
    var result = parser.parse(html, URL, 100);
    assertThat(result.vacancies()).hasSize(1);
    assertThat(result.skippedCards()).isEqualTo(1);
    assertThat(result.vacancies().getFirst().publishedAt()).isNull();
  }

  @Test
  void rejectsForeignOrNonNumericSponsoredIdentity() throws Exception {
    for (String response :
        new String[] {
          "https://outside.invalid/applicant/vacancy_response?vacancyId=102",
          "https://spb.hh.ru/applicant/vacancy_response?vacancyId=invalid"
        }) {
      var html =
          HtmlVacancyParserTest.fixture(RESULTS)
              .replace("https://spb.hh.ru/applicant/vacancy_response?vacancyId=102", response);
      var result = parser.parse(html, URL, 100);
      assertThat(result.vacancies()).hasSize(1);
      assertThat(result.skippedCards()).isEqualTo(1);
    }
  }

  @Test
  void limitsResultsAndSupportsFixedAmount() throws Exception {
    var html =
        HtmlVacancyParserTest.fixture(RESULTS)
            .replace("от <data value=\"2500.125\">", "<data value=\"2500.125\">");
    var result = parser.parse(html, URL, 100).vacancies().get(1);
    assertThat(result.salaryFrom()).isEqualTo(result.salaryTo());
    assertThat(parser.parse(html, URL, 1).vacancies()).hasSize(1);
  }
}
