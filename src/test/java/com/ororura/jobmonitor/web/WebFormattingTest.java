package com.ororura.jobmonitor.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.ororura.jobmonitor.collection.CollectionRun;
import com.ororura.jobmonitor.collection.CollectionRunStatus;
import com.ororura.jobmonitor.vacancy.Vacancy;
import com.ororura.jobmonitor.vacancy.VacancyStatus;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class WebFormattingTest {
  private static final String FAKE = "FAKE";

  private final Instant now = Instant.parse("2026-10-13T12:00:00Z");
  private final WebFormatting display = new WebFormatting(Clock.fixed(now, ZoneOffset.UTC));

  @Test
  void formatsSalaryBoundsWithoutLosingPrecision() {
    assertThat(display.salary(vacancy(null, null, null))).isEqualTo("Не указана");
    assertThat(display.salary(vacancy("100000.01", null, "RUB"))).isEqualTo("от 100 000,01 RUB");
    assertThat(display.salary(vacancy(null, "2500.125", "USD"))).isEqualTo("до 2 500,125 USD");
    assertThat(display.salary(vacancy("10", "20", null))).isEqualTo("10 – 20");
  }

  @Test
  void calculatesFinishedAndActiveDurationAndDoesNotRevealUnknownDiagnostics() {
    var run =
        new CollectionRun(
            null,
            null,
            "test",
            FAKE,
            CollectionRunStatus.RUNNING,
            now.minusSeconds(65),
            null,
            0,
            0,
            0,
            null);
    assertThat(display.duration(run)).isEqualTo("1 мин 5 с");
    var failed =
        new CollectionRun(
            null,
            null,
            "test",
            FAKE,
            CollectionRunStatus.FAILED,
            now.minusSeconds(65),
            now.minusSeconds(60),
            0,
            0,
            0,
            "secret=/private/cookies");
    assertThat(display.duration(failed)).isEqualTo("5 с");
    assertThat(display.diagnostic(failed)).doesNotContain("secret", "/private");
    assertThat(display.time(null)).isEqualTo("—");
  }

  @Test
  void formPreservesExpressionAndDefersIntervalRulesToService() {
    var form = new SearchForm("Name", "  (Java OR \"Spring Boot\")  ", "wrong", false, FAKE);
    assertThat(form.request().queryText()).isEqualTo(form.queryText());
    assertThat(form.request().intervalMinutes()).isNull();
  }

  private static Vacancy vacancy(String from, String to, String currency) {
    return new Vacancy(
        null,
        FAKE,
        "1",
        "Java",
        null,
        null,
        from == null ? null : new BigDecimal(from),
        to == null ? null : new BigDecimal(to),
        currency,
        "https://example.invalid/1",
        null,
        null,
        null,
        VacancyStatus.NEW);
  }
}
