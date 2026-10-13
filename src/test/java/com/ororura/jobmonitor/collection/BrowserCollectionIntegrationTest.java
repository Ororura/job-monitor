package com.ororura.jobmonitor.collection;

import static org.assertj.core.api.Assertions.assertThat;

import com.ororura.jobmonitor.search.SearchQueryRequest;
import com.ororura.jobmonitor.search.SearchService;
import com.ororura.jobmonitor.source.LocalVacancyServer;
import com.ororura.jobmonitor.vacancy.VacancyService;
import com.ororura.jobmonitor.vacancy.VacancyStatus;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@Tag("browser")
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
    properties = {
      "app.playwright.enabled=true",
      "app.playwright.access-confirmed=true",
      "app.playwright.navigation-timeout=5s",
      "app.playwright.operation-timeout=1s",
      "app.collection.enabled=true",
      "app.collection.poll-interval=1h"
    })
@Import(CollectionTestConfiguration.class)
class BrowserCollectionIntegrationTest {
  private static final String SOURCE = "PLAYWRIGHT";
  private static final String VACANCY_COUNT = "SELECT COUNT(*) FROM vacancies";
  private static final LocalVacancyServer SERVER = startServer();
  @TempDir static Path directory;
  @Autowired private CollectionDispatcher dispatcher;
  @Autowired private CollectionScheduler scheduler;
  @Autowired private SearchService searches;
  @Autowired private VacancyService vacancies;
  @Autowired private CollectionRunRepository runs;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private CollectionTestClock clock;

  @Autowired
  @Qualifier("collectionWorker")
  private ThreadPoolTaskExecutor worker;

  private static LocalVacancyServer startServer() {
    try {
      return new LocalVacancyServer();
    } catch (IOException exception) {
      throw new UncheckedIOException(exception);
    }
  }

  @DynamicPropertySource
  static void configuration(DynamicPropertyRegistry registry) {
    registry.add("job-monitor.data-directory", () -> directory.toString());
    registry.add("app.playwright.search-url", () -> SERVER.searchUrl().toString());
    registry.add(
        "app.playwright.headless", () -> System.getProperty("jobmonitor.browser.headless", "true"));
  }

  @BeforeEach
  void reset() {
    jdbc.update("DELETE FROM collection_runs");
    jdbc.update("DELETE FROM vacancies");
    jdbc.update("DELETE FROM search_queries");
    clock.set(CollectionTestClock.START);
    SERVER.fixture("results");
  }

  @AfterAll
  static void stopServer() {
    SERVER.close();
  }

  private void awaitWorker() throws Exception {
    // FIFO barrier waits for both collection finalization and dispatcher pending-set cleanup.
    worker.submit(() -> {}).get(20, TimeUnit.SECONDS);
  }

  private UUID search(boolean enabled) {
    return searches
        .create(
            new SearchQueryRequest(
                "Browser", "(Java OR Kotlin) AND NOT \"senior\"", 10, enabled, SOURCE))
        .id();
  }

  @Test
  void manualAndScheduledRunsShareIngestionHistoryAndFixedDelayPreservingStatus() throws Exception {
    UUID id = search(true);
    dispatcher.runManually(id);
    awaitWorker();
    var firstRun = runs.findAll(id, null, 0, 20).content().getFirst();
    assertThat(firstRun.status()).isEqualTo(CollectionRunStatus.SUCCESS);
    assertThat(firstRun.source()).isEqualTo(SOURCE);
    assertThat(firstRun.createdCount()).isEqualTo(3);
    assertThat(firstRun.fetchedCount()).isEqualTo(3);
    var initial = vacancies.findAll(null, SOURCE, id, 0, 20, "firstSeenAt,asc").content();
    assertThat(initial).hasSize(3);
    var precise =
        initial.stream().filter(v -> v.externalId().equals("101")).findFirst().orElseThrow();
    assertThat(precise.salaryFrom()).isEqualTo(new BigDecimal("100000.123456789"));
    assertThat(precise.salaryTo()).isEqualTo(new BigDecimal("200000.987654321"));
    var unknown =
        initial.stream().filter(v -> v.externalId().equals("102")).findFirst().orElseThrow();
    assertThat(unknown.employerName()).isNull();
    assertThat(unknown.area()).isNull();
    vacancies.changeStatus(precise.id(), VacancyStatus.APPLIED);
    clock.set(clock.instant().plusSeconds(600));
    scheduler.poll();
    awaitWorker();
    var repeated = runs.findAll(id, null, 0, 20).content().getFirst();
    assertThat(repeated.status()).isEqualTo(CollectionRunStatus.SUCCESS);
    assertThat(repeated.createdCount()).isZero();
    assertThat(repeated.updatedCount()).isEqualTo(3);
    assertThat(jdbc.queryForObject(VACANCY_COUNT, Integer.class)).isEqualTo(3);
    var saved = vacancies.findById(precise.id());
    assertThat(saved.status()).isEqualTo(VacancyStatus.APPLIED);
    assertThat(saved.firstSeenAt()).isEqualTo(precise.firstSeenAt());
    assertThat(saved.lastSeenAt()).isEqualTo(clock.instant());
    assertThat(searches.findById(id).nextRunAt()).isEqualTo(repeated.finishedAt().plusSeconds(600));
  }

  @Test
  void multipleSearchesShareVacanciesAndFakeStillWorksAlongsideBrowser() throws Exception {
    UUID first = search(false);
    UUID second = search(false);
    UUID fake = searches.create(new SearchQueryRequest("Fake", "Java", 10, false)).id();
    dispatcher.runManually(first);
    awaitWorker();
    dispatcher.runManually(second);
    awaitWorker();
    int browserRequests = SERVER.requests().size();
    dispatcher.runManually(fake);
    awaitWorker();
    assertThat(SERVER.requests()).hasSize(browserRequests);
    assertThat(jdbc.queryForObject(VACANCY_COUNT, Integer.class)).isEqualTo(5);
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM vacancy_search_matches", Integer.class))
        .isEqualTo(8);
    assertThat(runs.findAll(fake, null, 0, 20).content().getFirst().source()).isEqualTo("FAKE");
    assertThat(searches.findById(first).nextRunAt()).isNull();
  }

  @ParameterizedTest
  @ValueSource(strings = {"blocked", "changed", "loading"})
  void recordsBrowserFailuresAndAllowsNextDueRun(String fixture) throws Exception {
    UUID id = search(true);
    SERVER.fixture(fixture);
    dispatcher.runManually(id);
    awaitWorker();
    var failed = runs.findAll(id, null, 0, 20).content().getFirst();
    assertThat(failed.status()).isEqualTo(CollectionRunStatus.FAILED);
    assertThat(failed.source()).isEqualTo(SOURCE);
    String reason =
        switch (fixture) {
          case "blocked" -> "BLOCKED";
          case "changed" -> "STRUCTURE";
          default -> "TIMEOUT";
        };
    assertThat(failed.errorMessage()).isEqualTo("Browser collection failed: " + reason);
    assertThat(failed.createdCount()).isZero();
    assertThat(jdbc.queryForObject(VACANCY_COUNT, Integer.class)).isZero();
    assertThat(searches.findById(id).nextRunAt()).isEqualTo(clock.instant().plusSeconds(600));
    SERVER.fixture("results");
    scheduler.poll();
    awaitWorker();
    assertThat(runs.findAll(id, null, 0, 20).content()).hasSize(1);
    clock.set(clock.instant().plusSeconds(600));
    scheduler.poll();
    awaitWorker();
    assertThat(runs.findAll(id, CollectionRunStatus.SUCCESS, 0, 20).content()).hasSize(1);
  }

  @Test
  void emptyAndMalformedPagesUseExistingCollectionSemantics() throws Exception {
    UUID empty = search(false);
    SERVER.fixture("empty");
    dispatcher.runManually(empty);
    awaitWorker();
    assertThat(runs.findAll(empty, null, 0, 20).content().getFirst().status())
        .isEqualTo(CollectionRunStatus.SUCCESS);
    assertThat(jdbc.queryForObject(VACANCY_COUNT, Integer.class)).isZero();
    UUID malformed = search(false);
    SERVER.fixture("malformed");
    dispatcher.runManually(malformed);
    awaitWorker();
    var result = runs.findAll(malformed, null, 0, 20).content().getFirst();
    assertThat(result.status()).isEqualTo(CollectionRunStatus.SUCCESS);
    assertThat(result.createdCount()).isEqualTo(1);
    assertThat(result.fetchedCount()).isEqualTo(1);
  }
}
