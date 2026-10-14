package com.ororura.jobmonitor.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.ororura.jobmonitor.collection.CollectionRun;
import com.ororura.jobmonitor.collection.CollectionRunRepository;
import com.ororura.jobmonitor.collection.CollectionRunStatus;
import com.ororura.jobmonitor.collection.CollectionService;
import com.ororura.jobmonitor.search.SearchQuery;
import com.ororura.jobmonitor.search.SearchQueryRequest;
import com.ororura.jobmonitor.search.SearchService;
import com.ororura.jobmonitor.source.FakeVacancySource;
import com.ororura.jobmonitor.vacancy.VacancyData;
import com.ororura.jobmonitor.vacancy.VacancyService;
import com.ororura.jobmonitor.vacancy.VacancyStatus;
import java.net.CookieManager;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import org.jsoup.Jsoup;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class WebMvcIntegrationTest {
  private static final String FAKE = "FAKE";
  private static final String JAVA = "Java";
  private static final String SOURCE_FIELD = "source";
  private static final String DELETE_SUFFIX = "/delete";
  private static final String POLL_TRIGGER = "every 8s";
  private static final String COLLECTION_DETAIL = "/collections/";
  private static final String TRUE = "true";

  private static final String SEARCHES = "/searches";
  private static final String EXPRESSION =
      "  (Java OR \"Java-разработчик\") AND (Junior OR стажёр)  ";
  @TempDir static Path directory;
  @LocalServerPort private int port;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private SearchService searches;
  @Autowired private VacancyService vacancies;
  @Autowired private CollectionService collections;
  @Autowired private CollectionRunRepository runs;
  @Autowired private Clock clock;

  @Autowired
  @Qualifier("collectionWorker")
  private ThreadPoolTaskExecutor worker;

  @MockitoBean private FakeVacancySource source;
  private HttpClient client;
  private String csrf;

  @DynamicPropertySource
  static void database(DynamicPropertyRegistry registry) {
    registry.add("job-monitor.data-directory", () -> directory.toString());
  }

  @BeforeEach
  void setup() throws Exception {
    drain();
    jdbc.update("DELETE FROM collection_runs");
    jdbc.update("DELETE FROM vacancies");
    jdbc.update("DELETE FROM search_queries");
    when(source.source()).thenReturn(FAKE);
    when(source.fetch(any())).thenAnswer(i -> new FakeVacancySource().fetch(i.getArgument(0)));
    client =
        HttpClient.newBuilder()
            .cookieHandler(new CookieManager())
            .connectTimeout(Duration.ofSeconds(5))
            .build();
    var form = get("/searches/new", false);
    assertThat(form.statusCode()).isEqualTo(200);
    csrf =
        java.util.Objects.requireNonNull(Jsoup.parse(form.body()).selectFirst("input[name=csrf]"))
            .val();
  }

  @AfterEach
  void close() {
    drain();
    client.close();
  }

  @Test
  void emptyDashboardRendersRealZeroCountsAndEmptyStates() throws Exception {
    var response = get("/", false);
    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(Jsoup.parse(response.body()).select(".stats .number").eachText())
        .containsExactly("0", "0", "0", "0 / 0");
    assertThat(response.body())
        .contains("Пока нет вакансий", "История пока пуста", "Нет активных запросов");
  }

  @Test
  void dashboardAggregatesSqliteWithin24HoursAndLimitsLatestRows() throws Exception {
    var search = search();
    searches.update(search.id(), new SearchQueryRequest(JAVA, JAVA, 10, true));
    var data = new FakeVacancySource().fetch(search);
    vacancies.saveAll(data, search.id());
    var now = clock.instant();
    var old = UUID.randomUUID();
    runs.insert(
        new CollectionRun(
            old,
            search.id(),
            "Old",
            FAKE,
            CollectionRunStatus.RUNNING,
            now.minus(Duration.ofHours(25)),
            null,
            0,
            0,
            0,
            null));
    runs.finish(old, CollectionRunStatus.SUCCESS, now, 0, 0, 0, null);
    var recent = UUID.randomUUID();
    runs.insert(
        new CollectionRun(
            recent,
            search.id(),
            "Recent",
            FAKE,
            CollectionRunStatus.RUNNING,
            now,
            null,
            0,
            0,
            0,
            null));
    runs.finish(
        recent, CollectionRunStatus.FAILED, now, 0, 0, 0, "password=private /internal/path");
    var response = get("/", false);
    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(Jsoup.parse(response.body()).select(".stats .number").eachText())
        .containsExactly("2", "2", "1", "0 / 1");
    assertThat(response.body())
        .contains("Java backend developer", "Recent")
        .doesNotContain("password=private", "/internal/path");
  }

  @Test
  void createsExactExpressionThroughHtmxFormAndKeepsRestCompatible() throws Exception {
    var response = post(SEARCHES, fields(JAVA, EXPRESSION, "30"), true);
    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(response.headers().firstValue("HX-Redirect")).contains(SEARCHES);
    var search = searches.findAll().getFirst();
    assertThat(search.queryText()).isEqualTo(EXPRESSION);
    assertThat(search.enabled()).isFalse();
    assertThat(get("/api/v1/search-queries", false).body())
        .contains("queryText", search.id().toString());
    assertThat(get(SEARCHES, false).body()).contains("Поисковый запрос сохранён.");
  }

  @Test
  void normalFormPostRedirectsWithoutJavascript() throws Exception {
    var response = post(SEARCHES, fields(JAVA, EXPRESSION, "30"), false);
    assertThat(response.statusCode()).isEqualTo(302);
    assertThat(searches.findAll().getFirst().queryText()).isEqualTo(EXPRESSION);
  }

  @ParameterizedTest
  @ValueSource(strings = {"name", "queryText", "intervalMinutes", SOURCE_FIELD})
  void invalidFormReturnsFieldErrorAndPreservesValues(String field) throws Exception {
    var values = new java.util.HashMap<>(fields(JAVA, EXPRESSION, "30"));
    values.put(
        field,
        field.equals("intervalMinutes") ? "bad" : field.equals(SOURCE_FIELD) ? "OTHER" : " ");
    var response = post(SEARCHES, values, true);
    assertThat(response.statusCode()).isEqualTo(422);
    var document = Jsoup.parse(response.body());
    assertThat(document.select("[aria-invalid=true]").attr("name")).isEqualTo(field);
    assertThat(document.select(".field-error")).hasSize(1);
    assertThat(searches.findAll()).isEmpty();
    assertThat(response.body()).doesNotContain("<html");
  }

  @Test
  void editToggleAndConfirmedDeleteUseExistingServices() throws Exception {
    var search = search();
    var path = SEARCHES + "/" + search.id();
    assertThat(get(path + "/edit", false).body()).contains("Редактирование запроса");
    assertThat(post(path, fields("Updated", EXPRESSION, "60"), true).statusCode()).isEqualTo(200);
    assertThat(searches.findById(search.id()).name()).isEqualTo("Updated");
    assertThat(post(path + "/toggle", Map.of(), true).statusCode()).isEqualTo(200);
    assertThat(searches.findById(search.id()).enabled()).isTrue();
    post(path + "/toggle", Map.of(), true);
    assertThat(searches.findById(search.id()).enabled()).isFalse();
    assertThat(get(path + DELETE_SUFFIX, false).body()).contains("Удалить запрос?");
    assertThat(searches.findAll()).hasSize(1);
    assertThat(post(path + DELETE_SUFFIX, Map.of(), true).headers().firstValue("HX-Redirect"))
        .contains(SEARCHES);
    assertThat(searches.findAll()).isEmpty();
  }

  @Test
  void repeatedManualCollectionPreservesDeduplicationAndStatus() throws Exception {
    var search = search();
    var path = SEARCHES + "/" + search.id() + "/run";
    assertThat(post(path, Map.of(), true).statusCode()).isEqualTo(202);
    drain();
    var vacancy =
        vacancies.findAll(null, null, null, 0, 20, "firstSeenAt,desc").content().getFirst();
    assertThat(
            post("/vacancies/" + vacancy.id() + "/status", Map.of("newStatus", "SAVED"), true)
                .statusCode())
        .isEqualTo(200);
    assertThat(post(path, Map.of(), true).statusCode()).isEqualTo(202);
    drain();
    assertThat(vacancies.findAll(null, null, null, 0, 20, "firstSeenAt,desc").totalElements())
        .isEqualTo(2);
    assertThat(vacancies.findById(vacancy.id()).status()).isEqualTo(VacancyStatus.SAVED);
    assertThat(collections.findAll(search.id(), null, 0, 20).content())
        .hasSize(2)
        .allMatch(r -> r.status() == CollectionRunStatus.SUCCESS);
  }

  @Test
  void asyncConflictAndRunningFragmentsAreObservableWithoutBlocking() throws Exception {
    var search = search();
    var started = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    when(source.fetch(any()))
        .thenAnswer(
            i -> {
              started.countDown();
              assertThat(release.await(10, TimeUnit.SECONDS)).isTrue();
              return new FakeVacancySource().fetch(i.getArgument(0));
            });
    try {
      var path = SEARCHES + "/" + search.id() + "/run";
      assertThat(post(path, Map.of(), true).statusCode()).isEqualTo(202);
      assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
      var conflict = post(path, Map.of(), true);
      assertThat(conflict.statusCode()).isEqualTo(409);
      assertThat(conflict.body()).contains("уже в очереди");
      assertThat(get(SEARCHES, true).body()).contains(POLL_TRIGGER, "RUNNING");
      var running = get("/collections?status=RUNNING", true);
      assertThat(running.statusCode()).isEqualTo(200);
      assertThat(running.body()).contains(POLL_TRIGGER).doesNotContain("<html");
      var id = collections.findAll(null, null, 0, 20).content().getFirst().id();
      assertThat(get(COLLECTION_DETAIL + id, true).body()).contains("RUNNING", POLL_TRIGGER);
      release.countDown();
      drain();
      assertThat(get(COLLECTION_DETAIL + id, true).body())
          .contains("SUCCESS")
          .doesNotContain(POLL_TRIGGER);
    } finally {
      release.countDown();
    }
  }

  @Test
  void vacancyTableEscapesContentFormatsMissingFieldsAndSafeLinks() throws Exception {
    var vacancy =
        vacancies.save(
            new VacancyData(
                FAKE,
                "x",
                "<script>alert(1)</script>",
                "<b>Employer</b>",
                null,
                null,
                null,
                null,
                "https://example.invalid/x",
                null));
    var response = get("/vacancies", false);
    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(response.body())
        .contains("&lt;script&gt;", "Не указана", "noopener noreferrer", "Тестовые данные")
        .doesNotContain("<script>alert(1)</script>", "<b>Employer</b>");
    var document = Jsoup.parse(response.body());
    assertThat(document.select("select[name=newStatus]")).hasSize(1);
    post("/vacancies/" + vacancy.id() + "/status", Map.of("newStatus", "SAVED"), true);
    assertThat(vacancies.findById(vacancy.id()).status()).isEqualTo(VacancyStatus.SAVED);
  }

  @Test
  void filtersPaginationAndStatusMutationRetainFilters() throws Exception {
    var search = search();
    vacancies.saveAll(new FakeVacancySource().fetch(search), search.id());
    var response =
        get(
            "/vacancies?status=NEW&source=FAKE&searchQueryId="
                + search.id()
                + "&size=1&sort=firstSeenAt,asc",
            true);
    assertThat(response.statusCode()).isEqualTo(200);
    var doc = Jsoup.parse(response.body());
    assertThat(doc.select("tbody tr")).hasSize(1);
    assertThat(doc.select("a").eachAttr("href"))
        .anyMatch(
            h ->
                h.contains("page=1")
                    && h.contains("status=NEW")
                    && h.contains("sort=firstSeenAt,asc"));
    assertThat(get("/vacancies?page=1&size=1", true).statusCode()).isEqualTo(200);
    assertThat(get("/vacancies?source=PLAYWRIGHT", true).body()).contains("Нет вакансий");
    var vacancy =
        vacancies.findAll(null, null, null, 0, 20, "firstSeenAt,desc").content().getFirst();
    var changed =
        post(
            "/vacancies/" + vacancy.id() + "/status",
            Map.of(
                "newStatus",
                "SAVED",
                "status",
                "NEW",
                SOURCE_FIELD,
                FAKE,
                "searchQueryId",
                search.id().toString()),
            true);
    assertThat(changed.statusCode()).isEqualTo(200);
    assertThat(Jsoup.parse(changed.body()).select("tbody tr")).hasSize(1);
    assertThat(changed.body()).contains("Статус вакансии обновлён.");
  }

  @Test
  void historyFiltersPaginatesAndShowsDeletedSearchDetails() throws Exception {
    var search = search();
    for (int i = 0; i < 3; i++) {
      post(SEARCHES + "/" + search.id() + "/run", Map.of(), true);
      drain();
    }
    var history =
        get("/collections?status=SUCCESS&size=1&page=1&searchQueryId=" + search.id(), true);
    assertThat(history.statusCode()).isEqualTo(200);
    assertThat(Jsoup.parse(history.body()).select("tbody tr")).hasSize(1);
    assertThat(history.body()).contains("page=2", "status=SUCCESS");
    var run = collections.findAll(null, null, 0, 20).content().getFirst();
    searches.delete(search.id());
    var detail = get(COLLECTION_DETAIL + run.id(), false);
    assertThat(detail.statusCode()).isEqualTo(200);
    assertThat(detail.body())
        .contains(
            "Исходный поисковый запрос удалён",
            run.fetchedCount() + " / " + run.createdCount() + " / " + run.updatedCount());
  }

  @Test
  void searchNamesAreEscapedAndBrowserDisabledIsClearlyExplained() throws Exception {
    searches.create(
        new SearchQueryRequest(
            "<img src=x onerror=alert(1)>", "<b>Java</b>", 30, false, "PLAYWRIGHT"));
    var response = get(SEARCHES, false);
    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(response.body())
        .contains("&lt;img", "&lt;b&gt;", "PLAYWRIGHT выключен")
        .doesNotContain("<img src=x", "<b>Java</b>");
  }

  @Test
  void settingsAreReadonlyAndDoNotRevealEndpoint() throws Exception {
    var response = get("/settings", false);
    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(response.body())
        .contains("Headless", "перезапуска", directory.toString(), "Выключен");
    assertThat(Jsoup.parse(response.body()).select("form")).isEmpty();
  }

  @Test
  void csrfRejectsMutationAndGetNeverDeletes() throws Exception {
    var search = search();
    var request =
        HttpRequest.newBuilder(uri(SEARCHES + "/" + search.id() + DELETE_SUFFIX))
            .POST(HttpRequest.BodyPublishers.noBody())
            .build();
    assertThat(client.send(request, HttpResponse.BodyHandlers.ofString()).statusCode())
        .isEqualTo(403);
    assertThat(get(SEARCHES + "/" + search.id() + DELETE_SUFFIX, false).statusCode())
        .isEqualTo(200);
    assertThat(searches.findAll()).hasSize(1);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "/vacancies?page=-1",
        "/collections?size=101",
        "/vacancies?status=OTHER",
        "/searches/bad/edit"
      })
  void badArgumentsReturnSafeHtml(String path) throws Exception {
    var response = get(path, true);
    assertThat(response.statusCode()).isEqualTo(400);
    assertThat(response.body())
        .contains("Некорректные параметры")
        .doesNotContain("Exception", "stackTrace");
    assertThat(response.headers().firstValue("HX-Retarget")).contains("#notice");
  }

  @Test
  void missingRecordReturns404HtmlAndHistoryRestoreGetsFullPage() throws Exception {
    assertThat(get(COLLECTION_DETAIL + UUID.randomUUID(), false).statusCode()).isEqualTo(404);
    var request =
        HttpRequest.newBuilder(uri("/vacancies"))
            .header("HX-Request", TRUE)
            .header("HX-History-Restore-Request", TRUE)
            .GET()
            .build();
    assertThat(client.send(request, HttpResponse.BodyHandlers.ofString()).body())
        .contains("<!DOCTYPE html>", "<title>");
  }

  private SearchQuery search() {
    return searches.create(new SearchQueryRequest(JAVA, EXPRESSION, 30, false));
  }

  private static Map<String, String> fields(String name, String query, String interval) {
    return Map.of(
        "name", name, "queryText", query, "intervalMinutes", interval, SOURCE_FIELD, FAKE);
  }

  private HttpResponse<String> get(String path, boolean htmx) throws Exception {
    var builder = HttpRequest.newBuilder(uri(path)).timeout(Duration.ofSeconds(5));
    if (htmx) {
      builder.header("HX-Request", TRUE);
    }
    return client.send(builder.GET().build(), HttpResponse.BodyHandlers.ofString());
  }

  private HttpResponse<String> post(String path, Map<String, String> fields, boolean htmx)
      throws Exception {
    var values = new java.util.HashMap<>(fields);
    values.put("csrf", csrf);
    String body =
        values.entrySet().stream()
            .map(e -> encode(e.getKey()) + "=" + encode(e.getValue()))
            .collect(Collectors.joining("&"));
    var builder =
        HttpRequest.newBuilder(uri(path))
            .timeout(Duration.ofSeconds(5))
            .header("Content-Type", "application/x-www-form-urlencoded");
    if (htmx) {
      builder.header("HX-Request", TRUE);
    }
    return client.send(
        builder.POST(HttpRequest.BodyPublishers.ofString(body)).build(),
        HttpResponse.BodyHandlers.ofString());
  }

  private static String encode(String value) {
    return URLEncoder.encode(value, StandardCharsets.UTF_8);
  }

  private URI uri(String path) {
    return URI.create("http://127.0.0.1:" + port + path);
  }

  private void drain() {
    await()
        .atMost(Duration.ofSeconds(10))
        .until(
            () ->
                worker.getActiveCount() == 0
                    && worker.getThreadPoolExecutor().getQueue().isEmpty());
  }
}
