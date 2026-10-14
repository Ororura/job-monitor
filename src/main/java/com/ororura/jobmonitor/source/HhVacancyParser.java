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
public class HhVacancyParser {
  static final String CARD = "[data-qa=vacancy-serp__vacancy], [data-qa^='vacancy-serp__vacancy ']";
  static final String EMPTY = "[data-qa=empty-vacancy-search-block]";
  static final String BLOCKED =
      HtmlVacancyParser.BLOCKED
          + ", [data-qa*=captcha], form[action*=captcha], input[name=captcha], #captcha, .smart-captcha";
  static final String READY = CARD + ", " + EMPTY + ", " + BLOCKED;
  private static final Pattern VACANCY_PATH = Pattern.compile("/vacancy/(\\d+)/?");
  private static final Pattern NUMBER = Pattern.compile("\\d+(?:[.,]\\d+)?");
  private static final Pattern CURRENCY = Pattern.compile("[A-Z]{3}");

  public HtmlVacancyParser.ParsedPage parse(String html, String pageUrl, int limit) {
    var document = Jsoup.parse(html, pageUrl);
    if (document.selectFirst(BLOCKED) != null) {
      throw failure(BrowserSourceException.Reason.BLOCKED);
    }
    var cards = document.select(CARD);
    if (document.selectFirst(EMPTY) != null) {
      if (!cards.isEmpty()) {
        throw failure(BrowserSourceException.Reason.STRUCTURE);
      }
      return new HtmlVacancyParser.ParsedPage(List.of(), null, 0);
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
    if (vacancies.isEmpty()) {
      throw failure(BrowserSourceException.Reason.STRUCTURE);
    }
    var next = document.selectFirst("a[data-qa=pager-next]:not([aria-disabled=true])");
    String nextUrl = next == null ? null : next.absUrl("href");
    if (next != null && nextUrl.isBlank()) {
      throw failure(BrowserSourceException.Reason.STRUCTURE);
    }
    return new HtmlVacancyParser.ParsedPage(vacancies, nextUrl, skipped);
  }

  private static VacancyData parseCard(Element card) {
    var title = card.selectFirst("a[data-qa=serp-item__title][href]");
    if (title == null || title.text().isBlank()) {
      throw new IllegalArgumentException("Missing title");
    }
    URI link = URI.create(title.absUrl("href"));
    if (!trustedLink(link)) {
      throw new IllegalArgumentException("Invalid vacancy URL");
    }
    var match = VACANCY_PATH.matcher(link.getPath());
    String id = match.matches() ? match.group(1) : responseId(card);
    if (id == null) {
      throw new IllegalArgumentException("Missing stable identity");
    }
    var salary = salary(card);
    var published = card.selectFirst("time[datetime]");
    return new VacancyData(
        "HH",
        id,
        title.text().strip(),
        text(
            card,
            "[data-qa=vacancy-serp__vacancy-employer-text], [data-qa=vacancy-serp__vacancy-employer]"),
        text(card, "[data-qa=vacancy-serp__vacancy-address]"),
        salary.from(),
        salary.to(),
        salary.currency(),
        "https://hh.ru/vacancy/" + id,
        published == null ? null : instant(published.attr("datetime")));
  }

  private static boolean trustedLink(URI uri) {
    return "https".equalsIgnoreCase(uri.getScheme())
        && PlaywrightProperties.isHhHost(uri)
        && uri.getUserInfo() == null
        && (uri.getPort() == -1 || uri.getPort() == 443);
  }

  private static String responseId(Element card) {
    // Sponsored title URLs point to an ad redirect. Read the public response link's ID without
    // opening that link, following ads, or sending an application.
    var response = card.selectFirst("a[data-qa=vacancy-serp__vacancy_response][href]");
    if (response == null) {
      return null;
    }
    URI uri = URI.create(response.absUrl("href"));
    if (!trustedLink(uri)
        || !"/applicant/vacancy_response".equals(uri.getPath())
        || uri.getRawQuery() == null) {
      return null;
    }
    for (String parameter : uri.getRawQuery().split("&")) {
      String[] pair = parameter.split("=", 2);
      if (pair.length == 2
          && "vacancyId".equals(URLDecoder.decode(pair[0], StandardCharsets.UTF_8))) {
        String id = URLDecoder.decode(pair[1], StandardCharsets.UTF_8);
        return id.matches("\\d+") ? id : null;
      }
    }
    return null;
  }

  private static final int RANGE_BOUNDS = 2;

  private static Salary salary(Element card) {
    for (var currency : card.select("data[value]")) {
      Salary candidate = salaryAmount(currency);
      if (candidate != null) {
        return candidate;
      }
    }
    return new Salary(null, null, null);
  }

  private static Salary salaryAmount(Element currency) {
    String code = currency.attr("value");
    if (!CURRENCY.matcher(code).matches()) {
      return null;
    }
    var parent = currency.parent();
    if (parent == null) {
      return null;
    }
    List<BigDecimal> bounds =
        parent.select("data[value]").stream()
            .map(e -> e.attr("value").replaceAll("[\\s\\p{Zs}]", ""))
            .filter(v -> NUMBER.matcher(v).matches())
            .map(v -> new BigDecimal(v.replace(',', '.')))
            .toList();
    if (bounds.isEmpty() || bounds.size() > RANGE_BOUNDS) {
      return null;
    }
    BigDecimal first = bounds.getFirst();
    if (bounds.size() == RANGE_BOUNDS) {
      if (first.compareTo(bounds.get(1)) > 0) {
        throw new IllegalArgumentException("Invalid salary range");
      }
      return new Salary(first, bounds.get(1), code);
    }
    String label = parent.text().strip().toLowerCase(java.util.Locale.ROOT);
    if (label.startsWith("от")) {
      return new Salary(first, null, code);
    }
    if (label.startsWith("до")) {
      return new Salary(null, first, code);
    }
    return new Salary(first, first, code);
  }

  private static String text(Element card, String selector) {
    var element = card.selectFirst(selector);
    return element == null || element.text().isBlank() ? null : element.text().strip();
  }

  private static Instant instant(String value) {
    try {
      return Instant.parse(value);
    } catch (DateTimeParseException exception) {
      return null;
    }
  }

  private static BrowserSourceException failure(BrowserSourceException.Reason reason) {
    return new BrowserSourceException(reason);
  }

  private record Salary(BigDecimal from, BigDecimal to, String currency) {}
}
