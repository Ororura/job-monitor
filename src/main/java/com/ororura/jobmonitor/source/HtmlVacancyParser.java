package com.ororura.jobmonitor.source;

import com.ororura.jobmonitor.vacancy.VacancyData;
import java.math.BigDecimal;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Element;
import org.springframework.stereotype.Component;

@Component
public class HtmlVacancyParser {
  static final String BLOCKED = "[data-job-blocked], [data-captcha], iframe[src*='captcha']";
  static final String READY = "[data-job-results][data-ready=true], [data-job-empty], " + BLOCKED;
  private static final Pattern ID_PATH = Pattern.compile("/vacancies/([^/]+)$");

  public ParsedPage parse(String html, String pageUrl, int limit) {
    var document = Jsoup.parse(html, pageUrl);
    if (document.selectFirst(BLOCKED) != null) {
      throw new BrowserSourceException(BrowserSourceException.Reason.BLOCKED);
    }
    var results = document.selectFirst("[data-job-results][data-ready=true]");
    boolean empty = document.selectFirst("[data-job-empty]") != null;
    if (results == null) {
      if (empty) {
        return new ParsedPage(List.of(), null, 0);
      }
      throw structureError();
    }
    var cards = results.select("[data-job-card]");
    if (cards.isEmpty()) {
      if (empty) {
        return new ParsedPage(List.of(), null, 0);
      }
      throw structureError();
    }
    if (empty) {
      throw structureError();
    }
    List<VacancyData> vacancies = new ArrayList<>();
    int skipped = 0;
    for (Element card : cards) {
      try {
        vacancies.add(parseCard(card));
      } catch (IllegalArgumentException exception) {
        skipped++;
      }
      if (vacancies.size() >= limit) {
        break;
      }
    }
    // A page containing only unrecognizable cards cannot silently become a successful empty run.
    if (vacancies.isEmpty()) {
      throw structureError();
    }
    Element next = document.selectFirst("a[data-job-next]:not([aria-disabled=true])");
    String nextUrl = next == null ? null : optional(next.absUrl("href"));
    if (next != null && nextUrl == null) {
      throw structureError();
    }
    return new ParsedPage(vacancies, nextUrl, skipped);
  }

  private static VacancyData parseCard(Element card) {
    Element link = card.selectFirst("a[data-job-title][href]");
    if (link == null || link.text().isBlank()) {
      throw new IllegalArgumentException("Missing title");
    }
    URI url = URI.create(link.absUrl("href"));
    if (url.getHost() == null
        || url.getUserInfo() != null
        || !("https".equalsIgnoreCase(url.getScheme())
            || "http".equalsIgnoreCase(url.getScheme()))) {
      throw new IllegalArgumentException("Invalid vacancy URL");
    }
    // Canonical identity and URL exclude tracking query parameters and fragments.
    String canonical = canonicalUrl(url);
    String id = optional(card.attr("data-external-id"));
    if (id == null) {
      var matcher = ID_PATH.matcher(url.getPath());
      if (!matcher.find()) {
        throw new IllegalArgumentException("Missing stable identity");
      }
      id = matcher.group(1);
    }
    Element salary = card.selectFirst("[data-job-salary]");
    BigDecimal from = salary == null ? null : decimal(salary.attr("data-salary-from"));
    BigDecimal to = salary == null ? null : decimal(salary.attr("data-salary-to"));
    if ((from != null && from.signum() < 0)
        || (to != null && to.signum() < 0)
        || (from != null && to != null && from.compareTo(to) > 0)) {
      throw new IllegalArgumentException("Invalid salary bounds");
    }
    Element time = card.selectFirst("time[datetime]");
    return new VacancyData(
        "PLAYWRIGHT",
        id,
        link.text().strip(),
        text(card, "[data-job-employer]"),
        text(card, "[data-job-area]"),
        from,
        to,
        salary == null ? null : optional(salary.attr("data-currency")),
        canonical,
        time == null ? null : instant(time.attr("datetime")));
  }

  private static BigDecimal decimal(String value) {
    String normalized = optional(value);
    if (normalized == null) {
      return null;
    }
    return new BigDecimal(normalized.replaceAll("[\\s\\p{Zs}]", ""));
  }

  private static String canonicalUrl(URI url) {
    String base = url.getScheme() + "://" + url.getRawAuthority() + url.getRawPath();
    if (url.getRawQuery() == null) {
      return base;
    }
    List<String> parameters = new ArrayList<>();
    for (String parameter : url.getRawQuery().split("&")) {
      String key =
          URLDecoder.decode(parameter.split("=", 2)[0], StandardCharsets.UTF_8)
              .toLowerCase(java.util.Locale.ROOT);
      if (!key.equals("tracking") && !key.startsWith("utm_")) {
        parameters.add(parameter);
      }
    }
    // Some providers identify vacancies in the query string. Preserve semantic parameters.
    return parameters.isEmpty() ? base : base + "?" + String.join("&", parameters);
  }

  private static Instant instant(String value) {
    try {
      return Instant.parse(value);
    } catch (DateTimeParseException exception) {
      return null;
    }
  }

  private static String text(Element card, String selector) {
    Element field = card.selectFirst(selector);
    return field == null ? null : optional(field.text());
  }

  private static String optional(String value) {
    return value == null || value.isBlank() ? null : value.strip();
  }

  private static BrowserSourceException structureError() {
    return new BrowserSourceException(BrowserSourceException.Reason.STRUCTURE);
  }

  public record ParsedPage(List<VacancyData> vacancies, String nextUrl, int skippedCards) {
    public ParsedPage {
      vacancies = List.copyOf(vacancies);
    }
  }
}
