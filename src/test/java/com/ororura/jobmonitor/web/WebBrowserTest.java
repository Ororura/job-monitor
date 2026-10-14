package com.ororura.jobmonitor.web;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.ororura.jobmonitor.JobMonitorApplication;
import com.ororura.jobmonitor.collection.CollectionRunStatus;
import com.ororura.jobmonitor.collection.CollectionService;
import com.ororura.jobmonitor.search.SearchQueryRequest;
import com.ororura.jobmonitor.search.SearchService;
import com.ororura.jobmonitor.source.FakeVacancySource;
import com.ororura.jobmonitor.vacancy.VacancyService;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

@Tag("browser")
class WebBrowserTest {
  private static final String NAME_SELECTOR = "#name";
  private static final String QUERY_SELECTOR = "#queryText";
  private static final String FAKE = "FAKE";
  private static final String SUBMIT_SELECTOR = "button[type=submit]";
  private static final String CARD_TITLE = ".search-card h2";
  private static final String VACANCIES = "/vacancies";
  private static final String ROWS = "tbody tr";
  private static final String STATUS_SELECTOR = "select[name=newStatus]";
  private static final String SAVED = "SAVED";
  private static final String NOTICE_SUCCESS = ".notice-success";
  private static final String COLLECTIONS = "/collections";
  private static final String SUCCESS = "SUCCESS";
  private static final String SEARCHES = "/searches";
  private static final String NEW_SEARCH = "/searches/new";
  private static final String JAVA = "Java";
  private static final String FILTER_STATUS = "#filter-status";

  private static final String EXPRESSION =
      "  (Java OR \"Java-разработчик\") AND (Junior OR стажёр)  ";
  @TempDir static Path directory;
  private static ConfigurableApplicationContext application;
  private static String base;
  private Playwright playwright;
  private Browser browser;
  private BrowserContext context;
  private Page page;
  private final List<String> externalRequests = new ArrayList<>();
  private final List<String> errors = new ArrayList<>();

  @BeforeAll
  static void start() {
    startApplication();
  }

  private static void startApplication() {
    application =
        new SpringApplicationBuilder(JobMonitorApplication.class)
            .run(
                "--server.port=0",
                "--server.address=127.0.0.1",
                "--job-monitor.data-directory=" + directory,
                "--app.collection.enabled=false",
                "--app.playwright.enabled=false",
                "--spring.main.banner-mode=off");
    base =
        "http://127.0.0.1:"
            + java.util.Objects.requireNonNull(
                    ((WebServerApplicationContext) application).getWebServer())
                .getPort();
  }

  @AfterAll
  static void stop() {
    if (application != null) {
      application.close();
    }
  }

  @BeforeEach
  void setup() {
    drain();
    var jdbc = application.getBean(JdbcTemplate.class);
    jdbc.update("DELETE FROM collection_runs");
    jdbc.update("DELETE FROM vacancies");
    jdbc.update("DELETE FROM search_queries");
    playwright =
        Playwright.create(
            new Playwright.CreateOptions()
                .setEnv(java.util.Map.of("PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD", "1")));
    browser =
        playwright
            .chromium()
            .launch(
                new BrowserType.LaunchOptions()
                    .setHeadless(
                        Boolean.parseBoolean(
                            System.getProperty("jobmonitor.browser.headless", "true"))));
    context = browser.newContext(new Browser.NewContextOptions().setViewportSize(1440, 1000));
    context.route(
        "**/*",
        route -> {
          var uri = URI.create(route.request().url());
          // The offline suite permits only this Spring Boot application, even after a restart.
          if (uri.getHost().equals("127.0.0.1") && route.request().url().startsWith(base + "/")) {
            route.resume();
          } else {
            externalRequests.add(route.request().url());
            route.abort();
          }
        });
    page = context.newPage();
    page.setDefaultTimeout(10_000);
    page.onPageError(errors::add);
  }

  @AfterEach
  void closeBrowser() {
    context.close();
    browser.close();
    playwright.close();
    org.assertj.core.api.Assertions.assertThat(errors).isEmpty();
    org.assertj.core.api.Assertions.assertThat(externalRequests).isEmpty();
  }

  @Test
  void completeOfflineWorkflowSurvivesRestartWithoutDuplicates() throws Exception {
    page.navigate(base);
    assertThat(page.locator("h1")).hasText("Dashboard");
    page.locator("a[href='/searches/new']").first().click();
    page.locator(NAME_SELECTOR).fill("Java Junior");
    page.locator(QUERY_SELECTOR).fill(EXPRESSION);
    page.locator("#source").selectOption(FAKE);
    page.locator(SUBMIT_SELECTOR).click();
    assertThat(page.locator(CARD_TITLE)).hasText("Java Junior");
    org.assertj.core.api.Assertions.assertThat(
            application.getBean(SearchService.class).findAll().getFirst().queryText())
        .isEqualTo(EXPRESSION);
    runCollection();
    runCollection();
    page.navigate(base + VACANCIES);
    assertThat(page.locator(ROWS)).hasCount(2);
    page.locator(STATUS_SELECTOR).first().selectOption(SAVED);
    page.locator(".status-form button").first().click();
    assertThat(page.locator(NOTICE_SUCCESS)).hasText("Статус вакансии обновлён.");
    assertThat(page.locator(STATUS_SELECTOR).first()).hasValue(SAVED);
    page.navigate(base + COLLECTIONS);
    assertThat(page.locator(ROWS)).hasCount(2);
    page.locator("tbody a").first().click();
    assertThat(page.locator("#run-detail .badge")).hasText(SUCCESS);
    screenshot("workflow-history");
    application.close();
    startApplication();
    page.navigate(base + SEARCHES);
    assertThat(page.locator(CARD_TITLE)).hasText("Java Junior");
    page.navigate(base + VACANCIES);
    assertThat(page.locator(ROWS)).hasCount(2);
    assertThat(page.locator(STATUS_SELECTOR).first()).hasValue(SAVED);
    page.navigate(base + COLLECTIONS);
    assertThat(page.locator(ROWS)).hasCount(2);
  }

  @Test
  void formValidationEditToggleAndDeleteWorkWithHtmx() {
    page.navigate(base + NEW_SEARCH);
    page.locator(SUBMIT_SELECTOR).click();
    assertThat(page.locator("#name-error")).hasText("Укажите название запроса.");
    // HTMX restores focus during settling; wait before filling the replacement form.
    assertThat(page.locator("#search-form.htmx-settling")).hasCount(0);
    page.locator(NAME_SELECTOR).fill("Initial");
    page.locator(SUBMIT_SELECTOR).click();
    assertThat(page.locator("#query-error")).hasText("Укажите поисковое выражение.");
    assertThat(page.locator("#search-form.htmx-settling")).hasCount(0);
    page.locator(QUERY_SELECTOR).fill(EXPRESSION);
    assertThat(page.locator(QUERY_SELECTOR)).hasValue(EXPRESSION);
    page.locator("#intervalMinutes").fill("9");
    page.locator(SUBMIT_SELECTOR).click();
    assertThat(page.locator("#interval-error")).containsText("10 минут");
    assertThat(page.locator("#search-form.htmx-settling")).hasCount(0);
    page.locator("#intervalMinutes").fill("30");
    page.locator(SUBMIT_SELECTOR).click();
    assertThat(page.locator(CARD_TITLE)).hasText("Initial");
    page.locator("button")
        .filter(new com.microsoft.playwright.Locator.FilterOptions().setHasText("Включить"))
        .click();
    assertThat(page.locator(".search-card-header .badge").last()).hasText("ВКЛЮЧЁН");
    page.locator("button")
        .filter(new com.microsoft.playwright.Locator.FilterOptions().setHasText("Выключить"))
        .click();
    assertThat(page.locator(".search-card-header .badge").last()).hasText("ВЫКЛЮЧЕН");
    page.locator("a")
        .filter(new com.microsoft.playwright.Locator.FilterOptions().setHasText("Редактировать"))
        .click();
    page.locator(NAME_SELECTOR).fill("Updated");
    page.locator(SUBMIT_SELECTOR).click();
    assertThat(page.locator(CARD_TITLE)).hasText("Updated");
    page.locator(".search-card a.danger").click();
    assertThat(page.locator("h1")).hasText("Удалить запрос?");
    page.locator(SUBMIT_SELECTOR).click();
    assertThat(page.locator(".empty h2")).hasText("Создайте первый запрос");
  }

  @Test
  void vacancyFilterPaginationAndInlineStatusUseLocalFragments() {
    var searches = application.getBean(SearchService.class);
    var search = searches.create(new SearchQueryRequest(JAVA, JAVA, 30, false));
    application
        .getBean(VacancyService.class)
        .saveAll(new FakeVacancySource().fetch(search), search.id());
    page.navigate(base + "/vacancies?status=NEW&source=FAKE&size=1");
    assertThat(page.locator(ROWS)).hasCount(1);
    String first = page.locator("tbody .title-cell a").textContent();
    page.locator(".pagination a")
        .filter(new com.microsoft.playwright.Locator.FilterOptions().setHasText("Далее"))
        .click();
    assertThat(page.locator("tbody .title-cell a")).not().hasText(first);
    assertThat(page.locator(FILTER_STATUS)).hasValue("NEW");
    assertThat(page.locator("#filter-source")).hasValue(FAKE);
    page.locator(FILTER_STATUS).selectOption(SAVED);
    page.locator(".filters button").click();
    assertThat(page.locator(".empty h2")).containsText("Нет вакансий");
    page.locator(FILTER_STATUS).selectOption("NEW");
    page.locator(".filters button").click();
    page.locator(STATUS_SELECTOR).selectOption(SAVED);
    page.locator(".status-form button").click();
    assertThat(page.locator(NOTICE_SUCCESS)).containsText("обновлён");
    page.locator(FILTER_STATUS).selectOption(SAVED);
    page.locator(".filters button").click();
    assertThat(page.locator(STATUS_SELECTOR)).hasValue(SAVED);
  }

  @ParameterizedTest
  @ValueSource(ints = {1440, 390})
  void allPagesFitDesktopAndMobileAndSupportThemes(int width) throws Exception {
    page.setViewportSize(width, 950);
    for (String path : List.of("/", SEARCHES, VACANCIES, COLLECTIONS, "/settings", NEW_SEARCH)) {
      page.navigate(base + path);
      assertThat(page.locator("h1")).isVisible();
      assertThat(page.locator("#loading")).not().isVisible();
      noOverflow();
      screenshot(
          width
              + "-empty-"
              + (path.equals("/") ? "dashboard" : path.substring(1).replace('/', '-')));
    }
    page.navigate(base + NEW_SEARCH);
    page.locator(NAME_SELECTOR).fill("Очень длинный поисковый запрос " + JAVA.repeat(40));
    page.locator(QUERY_SELECTOR).fill(EXPRESSION + "x".repeat(200));
    page.locator(SUBMIT_SELECTOR).click();
    assertThat(page.locator(".search-card")).hasCount(1);
    noOverflow();
    page.locator(".search-card button.primary").click();
    assertThat(page.locator(NOTICE_SUCCESS)).containsText("принято в очередь");
    await()
        .atMost(Duration.ofSeconds(10))
        .until(
            () ->
                application
                        .getBean(CollectionService.class)
                        .findAll(null, CollectionRunStatus.SUCCESS, 0, 20)
                        .totalElements()
                    == 1);
    for (String path : List.of("/", SEARCHES, VACANCIES, COLLECTIONS, "/settings")) {
      page.navigate(base + path);
      noOverflow();
      screenshot(width + "-populated-" + (path.equals("/") ? "dashboard" : path.substring(1)));
      page.locator("#theme").selectOption("dark");
      assertThat(page.locator("html")).hasAttribute("data-theme", "dark");
      noOverflow();
      screenshot(width + "-dark-" + (path.equals("/") ? "dashboard" : path.substring(1)));
      page.locator("#theme").selectOption("light");
    }
    page.reload();
    assertThat(page.locator("#theme")).hasValue("light");
  }

  @Test
  void basicPagesAndPostFormsWorkWhenJavascriptIsDisabled() {
    try (var plain =
            browser.newContext(new Browser.NewContextOptions().setJavaScriptEnabled(false));
        var html = plain.newPage()) {
      html.navigate(base + NEW_SEARCH);
      html.locator(NAME_SELECTOR).fill("No JS");
      html.locator(QUERY_SELECTOR).fill(EXPRESSION);
      html.locator(SUBMIT_SELECTOR).click();
      assertThat(html.locator(CARD_TITLE)).hasText("No JS");
      html.locator(".search-card button.primary").click();
      assertThat(html.locator(NOTICE_SUCCESS)).containsText("принято в очередь");
    }
  }

  @Test
  void activeRunDetailsAndPaginatedHistoryRefreshThroughPolling() {
    var searches = application.getBean(SearchService.class);
    var query = searches.create(new SearchQueryRequest("Polling", JAVA, 30, false));
    var runs = application.getBean(com.ororura.jobmonitor.collection.CollectionRunRepository.class);
    var now = java.time.Instant.now();
    var older = java.util.UUID.randomUUID();
    var newer = java.util.UUID.randomUUID();
    runs.insert(
        new com.ororura.jobmonitor.collection.CollectionRun(
            older,
            query.id(),
            "Older",
            FAKE,
            CollectionRunStatus.RUNNING,
            now.minusSeconds(1),
            null,
            0,
            0,
            0,
            null));
    runs.insert(
        new com.ororura.jobmonitor.collection.CollectionRun(
            newer,
            query.id(),
            "Newer",
            FAKE,
            CollectionRunStatus.RUNNING,
            now,
            null,
            0,
            0,
            0,
            null));
    page.navigate(base + "/collections?page=1&size=1&searchQueryId=" + query.id());
    assertThat(page.locator("tbody .title-cell a")).hasText("Older");
    runs.finish(older, CollectionRunStatus.SUCCESS, now, 3, 2, 0, null);
    assertThat(page.locator("tbody .badge.SUCCESS"))
        .hasText(
            SUCCESS,
            new com.microsoft.playwright.assertions.LocatorAssertions.HasTextOptions()
                .setTimeout(12_000));
    assertThat(page.locator(".pagination")).containsText("Страница 2");
    page.navigate(base + "/collections/" + newer);
    assertThat(page.locator("#run-detail .badge")).hasText("RUNNING");
    runs.finish(newer, CollectionRunStatus.SUCCESS, now, 3, 0, 2, null);
    assertThat(page.locator("#run-detail .badge"))
        .hasText(
            SUCCESS,
            new com.microsoft.playwright.assertions.LocatorAssertions.HasTextOptions()
                .setTimeout(12_000));
    org.assertj.core.api.Assertions.assertThat(
            page.locator("#run-detail").getAttribute("hx-trigger"))
        .isNull();
  }

  private void runCollection() {
    page.navigate(base + SEARCHES);
    page.locator(".search-card button.primary").click();
    assertThat(page.locator(NOTICE_SUCCESS)).containsText("принято в очередь");
    drain();
    page.navigate(base + COLLECTIONS);
    assertThat(page.locator(ROWS).first().locator(".badge.SUCCESS")).hasText(SUCCESS);
  }

  private void noOverflow() {
    if (Boolean.FALSE.equals(
        page.evaluate("document.documentElement.scrollWidth <= window.innerWidth"))) {
      page.screenshot(
          new Page.ScreenshotOptions()
              .setPath(Path.of("build/reports/web-visual/overflow.png"))
              .setFullPage(true));
    }
    org.assertj.core.api.Assertions.assertThat(
            page.evaluate("document.documentElement.scrollWidth <= window.innerWidth"))
        .as(
            page.url()
                + " overflow: "
                + page.evaluate(
                    "JSON.stringify([...document.querySelectorAll('body *')].filter(e=>e.getBoundingClientRect().right>innerWidth).slice(0,12).map(e=>[e.tagName,e.className,e.getBoundingClientRect().width,e.getBoundingClientRect().right,e.textContent.slice(0,30),e.parentElement.className]))"))
        .isEqualTo(true);
  }

  private void screenshot(String name) throws Exception {
    Path destination = Path.of("build/reports/web-visual", name + ".png");
    Files.createDirectories(Path.of("build/reports/web-visual"));
    page.screenshot(new Page.ScreenshotOptions().setPath(destination).setFullPage(true));
  }

  private static void drain() {
    var worker = application.getBean("collectionWorker", ThreadPoolTaskExecutor.class);
    await()
        .atMost(Duration.ofSeconds(10))
        .until(
            () ->
                worker.getActiveCount() == 0
                    && worker.getThreadPoolExecutor().getQueue().isEmpty());
  }
}
