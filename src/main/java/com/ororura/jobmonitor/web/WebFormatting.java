package com.ororura.jobmonitor.web;

import com.ororura.jobmonitor.collection.CollectionRun;
import com.ororura.jobmonitor.vacancy.Vacancy;
import java.math.BigDecimal;
import java.text.NumberFormat;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import org.springframework.stereotype.Component;

@Component("display")
public class WebFormatting {
  private final Clock clock;

  public WebFormatting(Clock clock) {
    this.clock = clock;
  }

  public String time(Instant instant) {
    return instant == null
        ? "—"
        : DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm:ss")
            .withZone(ZoneId.systemDefault())
            .format(instant);
  }

  public String zone() {
    return ZoneId.systemDefault().getId();
  }

  public String salary(Vacancy vacancy) {
    if (vacancy.salaryFrom() == null && vacancy.salaryTo() == null) {
      return "Не указана";
    }
    String bounds =
        vacancy.salaryFrom() == null
            ? "до " + amount(vacancy.salaryTo())
            : vacancy.salaryTo() == null
                ? "от " + amount(vacancy.salaryFrom())
                : amount(vacancy.salaryFrom()) + " – " + amount(vacancy.salaryTo());
    return vacancy.currency() == null ? bounds : bounds + " " + vacancy.currency();
  }

  private static String amount(BigDecimal amount) {
    NumberFormat format = NumberFormat.getNumberInstance(Locale.forLanguageTag("ru-RU"));
    format.setMaximumFractionDigits(Math.max(0, amount.scale()));
    return format.format(amount);
  }

  public String duration(CollectionRun run) {
    long seconds =
        Math.max(
            0,
            Duration.between(
                    run.startedAt(), run.finishedAt() == null ? clock.instant() : run.finishedAt())
                .toSeconds());
    return seconds < 60 ? seconds + " с" : seconds / 60 + " мин " + seconds % 60 + " с";
  }

  public String diagnostic(CollectionRun run) {
    // Persisted messages are sanitized by collection services. Only known categories are exposed.
    String error = run.errorMessage();
    if (error == null) {
      return "—";
    }
    for (var reason : com.ororura.jobmonitor.source.BrowserSourceException.Reason.values()) {
      if (error.equals("Browser collection failed: " + reason.name())) {
        return "Ошибка браузерного источника: " + reason.name();
      }
    }
    return run.status() == com.ororura.jobmonitor.collection.CollectionRunStatus.INTERRUPTED
        ? "Приложение завершилось до окончания сбора"
        : "Сбор не завершён. Проверьте источник и повторите запуск.";
  }
}
