package com.ororura.jobmonitor.collection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;

import com.ororura.jobmonitor.search.SearchQuery;
import com.ororura.jobmonitor.search.SearchQueryRequest;
import com.ororura.jobmonitor.search.SearchService;
import com.ororura.jobmonitor.source.FakeVacancySource;
import com.ororura.jobmonitor.source.VacancySource;
import com.ororura.jobmonitor.vacancy.Vacancy;
import com.ororura.jobmonitor.vacancy.VacancyPage;
import com.ororura.jobmonitor.vacancy.VacancyStatus;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
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
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {"app.collection.queue-capacity=1"})
@Import(CollectionTestConfiguration.class)
class CollectionApiTest {
  private static final String SEARCH_FILTER = "?searchQueryId=";
  private static final String RUNS = "/api/v1/collection-runs";
  private static final String SEARCHES = "/api/v1/search-queries/";
  private static final String GET = "GET";
  private static final String POST = "POST";
  private static final String JAVA = "Java";
  private static final String RUN = "/run";
  private static final String VACANCIES = "/api/v1/vacancies";
  @TempDir static Path directory;
  @LocalServerPort private int port;
  @Autowired private ObjectMapper mapper;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private SearchService searches;
  @Autowired private CollectionTestClock clock;
  @Autowired private CollectionProperties properties;
  @Autowired private CollectionDispatcher dispatcher;

  @Autowired
  @Qualifier("collectionWorker")
  private ThreadPoolTaskExecutor worker;

  @MockitoBean private VacancySource source;
  private HttpClient client;

  @DynamicPropertySource
  static void database(DynamicPropertyRegistry registry) {
    registry.add("job-monitor.data-directory", () -> directory.toString());
  }

  @BeforeEach
  void reset() {
    jdbc.update("DELETE FROM collection_runs");
    jdbc.update("DELETE FROM vacancies");
    jdbc.update("DELETE FROM search_queries");
    clock.set(CollectionTestClock.START);
    client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    when(source.source()).thenReturn("FAKE");
    when(source.fetch(any()))
        .thenAnswer(invocation -> new FakeVacancySource().fetch(invocation.getArgument(0)));
  }

  @AfterEach
  void close() throws Exception {
    drainWorker();
    client.close();
  }

  @Test
  void manualHttpRunCreatesVacanciesAndRepeatsWithoutDuplicatesOrStatusReset() throws Exception {
    assertThat(properties.enabled()).isFalse();
    var search = search(false);
    assertThat(send(POST, SEARCHES + search.id() + RUN).statusCode()).isEqualTo(202);
    drainWorker();
    var first = page("").content().getFirst();
    assertThat(first.status()).isEqualTo(CollectionRunStatus.SUCCESS);
    assertThat(first.fetchedCount()).isEqualTo(3);
    assertThat(first.createdCount()).isEqualTo(2);
    assertThat(first.updatedCount()).isZero();
    assertThat(first.startedAt()).isEqualTo(clock.instant());
    var vacancies = mapper.readValue(send(GET, VACANCIES).body(), VacancyPage.class);
    assertThat(vacancies.totalElements()).isEqualTo(2);
    var vacancy = vacancies.content().getFirst();
    var patch =
        HttpRequest.newBuilder(uri(VACANCIES + "/" + vacancy.id() + "/status"))
            .timeout(Duration.ofSeconds(5))
            .header("Content-Type", "application/json")
            .method("PATCH", HttpRequest.BodyPublishers.ofString("{\"status\":\"SAVED\"}"))
            .build();
    assertThat(client.send(patch, HttpResponse.BodyHandlers.ofString()).statusCode())
        .isEqualTo(200);
    clock.set(clock.instant().plusSeconds(60));
    assertThat(send(POST, SEARCHES + search.id() + RUN).statusCode()).isEqualTo(202);
    drainWorker();
    var second = page(SEARCH_FILTER + search.id() + "&status=SUCCESS&page=0&size=1");
    assertThat(second.totalElements()).isEqualTo(2);
    assertThat(second.content()).hasSize(1);
    assertThat(second.content().getFirst().createdCount()).isZero();
    assertThat(second.content().getFirst().updatedCount()).isEqualTo(2);
    assertThat(mapper.readValue(send(GET, VACANCIES).body(), VacancyPage.class).totalElements())
        .isEqualTo(2);
    assertThat(
            mapper
                .readValue(send(GET, VACANCIES + "/" + vacancy.id()).body(), Vacancy.class)
                .status())
        .isEqualTo(VacancyStatus.SAVED);
    assertThat(searches.findById(search.id()).enabled()).isFalse();
    assertThat(searches.findById(search.id()).nextRunAt()).isNull();
    var detail = send(GET, RUNS + "/" + first.id());
    assertThat(detail.statusCode()).isEqualTo(200);
    assertThat(mapper.readValue(detail.body(), CollectionRun.class)).isEqualTo(first);
    assertThat(send("DELETE", SEARCHES + search.id()).statusCode()).isEqualTo(204);
    assertThat(page("").totalElements()).isEqualTo(2);
    var detached = mapper.readValue(send(GET, RUNS + "/" + first.id()).body(), CollectionRun.class);
    assertThat(detached.searchQueryId()).isNull();
    assertThat(detached.searchQueryName()).isEqualTo(JAVA);
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM collection_runs", Integer.class))
        .isEqualTo(2);
    assertThat(page(SEARCH_FILTER + UUID.randomUUID()).content()).isEmpty();
    assertThat(page("?status=FAILED").content()).isEmpty();
    assertThat(page("?page=2&size=1").content()).isEmpty();
  }

  @Test
  void returns202WhileSourceIsBlockedAnd409ForRunningQueuedAndFullQueue() throws Exception {
    var first = search(true);
    var second = search(true);
    var third = search(true);
    var started = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    doAnswer(
            invocation -> {
              started.countDown();
              assertThat(release.await(5, TimeUnit.SECONDS)).isTrue();
              return new FakeVacancySource().fetch(invocation.getArgument(0));
            })
        .when(source)
        .fetch(any());
    try {
      assertThat(send(POST, SEARCHES + first.id() + RUN).statusCode()).isEqualTo(202);
      assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
      assertThat(page("?status=RUNNING").content()).hasSize(1);
      assertThat(send(POST, SEARCHES + first.id() + RUN).statusCode()).isEqualTo(409);
      assertThat(send(POST, SEARCHES + second.id() + RUN).statusCode()).isEqualTo(202);
      assertThat(send(POST, SEARCHES + second.id() + RUN).statusCode()).isEqualTo(409);
      var conflict = send(POST, SEARCHES + third.id() + RUN);
      assertThat(conflict.statusCode()).isEqualTo(409);
      assertThat(mapper.readTree(conflict.body()).get("detail").asString()).contains("queue");
    } finally {
      release.countDown();
    }
    drainWorker();
    assertThat(page("?status=SUCCESS").content()).hasSize(2);
    assertThat(send(POST, SEARCHES + third.id() + RUN).statusCode()).isEqualTo(202);
    drainWorker();
    assertThat(page("?status=SUCCESS").content()).hasSize(3);
  }

  @Test
  void disabledQueuedAutomaticJobIsSkippedBeforeSourceAccess() throws Exception {
    var first = search(true);
    var queued = search(true);
    var started = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    doAnswer(
            invocation -> {
              started.countDown();
              assertThat(release.await(5, TimeUnit.SECONDS)).isTrue();
              return new FakeVacancySource().fetch(invocation.getArgument(0));
            })
        .when(source)
        .fetch(any());
    try {
      assertThat(send(POST, SEARCHES + first.id() + RUN).statusCode()).isEqualTo(202);
      assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
      assertThat(dispatcher.submit(queued.id(), true)).isTrue();
      searches.update(queued.id(), new SearchQueryRequest(JAVA, JAVA, 10, false));
    } finally {
      release.countDown();
    }
    drainWorker();
    assertThat(page(SEARCH_FILTER + queued.id()).content()).isEmpty();
    assertThat(send(POST, SEARCHES + queued.id() + RUN).statusCode()).isEqualTo(202);
    drainWorker();
    assertThat(page(SEARCH_FILTER + queued.id()).content()).hasSize(1);
  }

  @Test
  void missingRecordsReturn404() throws Exception {
    assertThat(send(POST, SEARCHES + UUID.randomUUID() + RUN).statusCode()).isEqualTo(404);
    assertThat(send(GET, RUNS + "/" + UUID.randomUUID()).statusCode()).isEqualTo(404);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "?page=-1",
        "?size=0",
        "?size=101",
        "?page=bad",
        "?status=UNKNOWN",
        "?searchQueryId=bad",
        "/bad"
      })
  void invalidHistoryArgumentsReturn400(String suffix) throws Exception {
    assertThat(send(GET, RUNS + suffix).statusCode()).isEqualTo(400);
  }

  @Test
  void invalidManualUuidReturns400() throws Exception {
    assertThat(send(POST, SEARCHES + "bad" + RUN).statusCode()).isEqualTo(400);
  }

  private SearchQuery search(boolean enabled) {
    return searches.create(new SearchQueryRequest(JAVA, JAVA, 10, enabled));
  }

  private CollectionRunPage page(String suffix) throws Exception {
    var response = send(GET, RUNS + suffix);
    assertThat(response.statusCode()).isEqualTo(200);
    return mapper.readValue(response.body(), CollectionRunPage.class);
  }

  private HttpResponse<String> send(String method, String path) throws Exception {
    return client.send(
        HttpRequest.newBuilder(uri(path))
            .timeout(Duration.ofSeconds(5))
            .method(method, HttpRequest.BodyPublishers.noBody())
            .build(),
        HttpResponse.BodyHandlers.ofString());
  }

  private URI uri(String path) {
    return URI.create("http://127.0.0.1:" + port + path);
  }

  private void drainWorker() {
    await()
        .atMost(Duration.ofSeconds(5))
        .until(
            () ->
                worker.getActiveCount() == 0
                    && worker.getThreadPoolExecutor().getQueue().isEmpty());
  }
}
