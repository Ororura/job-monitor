package com.ororura.jobmonitor.collection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.ororura.jobmonitor.search.SearchQuery;
import com.ororura.jobmonitor.search.SearchQueryRequest;
import com.ororura.jobmonitor.search.SearchService;
import com.ororura.jobmonitor.source.FakeVacancySource;
import com.ororura.jobmonitor.source.VacancySource;
import com.ororura.jobmonitor.vacancy.Vacancy;
import com.ororura.jobmonitor.vacancy.VacancyData;
import com.ororura.jobmonitor.vacancy.VacancyService;
import com.ororura.jobmonitor.vacancy.VacancyStatus;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Import(CollectionTestConfiguration.class)
class CollectionServiceTest {
  private static final String JAVA = "Java";
  private static final String FAKE = "FAKE";
  private static final String VACANCY_COUNT = "SELECT COUNT(*) FROM vacancies";
  @TempDir static Path directory;
  @Autowired private CollectionService service;
  @Autowired private CollectionRunRepository runs;
  @Autowired private SearchService searches;
  @Autowired private VacancyService vacancies;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private CollectionTestClock clock;
  @MockitoBean private VacancySource source;

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
  }

  @Test
  void firstRunUsesCompletionTimeAndIndividualIntervalsWithoutFetchingInTransaction() {
    var first = search(10, true);
    var second = search(60, true);
    when(source.source()).thenReturn(FAKE);
    when(source.fetch(any()))
        .thenAnswer(
            invocation -> {
              assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
              var running = runs.findRunning();
              assertThat(running).hasSize(1);
              assertThat(running.getFirst().startedAt()).isEqualTo(clock.instant());
              clock.set(clock.instant().plusSeconds(45));
              return new FakeVacancySource().fetch(invocation.getArgument(0));
            });
    assertThat(first.nextRunAt()).isNull();
    service.collect(first.id(), true);
    assertThat(searches.findById(first.id()).nextRunAt())
        .isEqualTo(clock.instant().plusSeconds(600));
    var completed = runs.findAll(first.id(), null, 0, 20).content().getFirst();
    assertThat(completed.status()).isEqualTo(CollectionRunStatus.SUCCESS);
    assertThat(completed.fetchedCount()).isEqualTo(3);
    assertThat(completed.createdCount()).isEqualTo(2);
    assertThat(completed.updatedCount()).isZero();
    assertThat(completed.finishedAt()).isEqualTo(clock.instant());
    service.collect(second.id(), true);
    assertThat(searches.findById(second.id()).nextRunAt())
        .isEqualTo(clock.instant().plusSeconds(3600));
    assertThat(jdbc.queryForObject(VACANCY_COUNT, Integer.class)).isEqualTo(2);
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM vacancy_search_matches", Integer.class))
        .isEqualTo(4);
  }

  @Test
  void repeatUpdatesDistinctExistingVacanciesWithoutResettingStatus() {
    var search = search(30, true);
    useFakeSource();
    service.collect(search.id(), false);
    List<Vacancy> first =
        vacancies.findAll(null, null, search.id(), 0, 20, "firstSeenAt,asc").content();
    vacancies.changeStatus(first.getFirst().id(), VacancyStatus.APPLIED);
    clock.set(clock.instant().plusSeconds(60));
    service.collect(search.id(), false);
    var latest = runs.findAll(search.id(), null, 0, 20).content().getFirst();
    assertThat(latest.createdCount()).isZero();
    assertThat(latest.updatedCount()).isEqualTo(2);
    assertThat(latest.fetchedCount()).isEqualTo(3);
    assertThat(jdbc.queryForObject(VACANCY_COUNT, Integer.class)).isEqualTo(2);
    var saved = vacancies.findById(first.getFirst().id());
    assertThat(saved.status()).isEqualTo(VacancyStatus.APPLIED);
    assertThat(saved.firstSeenAt()).isEqualTo(first.getFirst().firstSeenAt());
    assertThat(saved.lastSeenAt()).isEqualTo(clock.instant());
  }

  @Test
  void completionUsesIntervalChangedDuringFetching() {
    var search = search(10, true);
    when(source.source()).thenReturn(FAKE);
    when(source.fetch(any()))
        .thenAnswer(
            invocation -> {
              searches.update(search.id(), new SearchQueryRequest(JAVA, JAVA, 60, true));
              clock.set(clock.instant().plusSeconds(120));
              return List.of();
            });
    service.collect(search.id(), true);
    assertThat(searches.findById(search.id()).nextRunAt())
        .isEqualTo(clock.instant().plusSeconds(3600));
  }

  @Test
  void disabledManualRunDoesNotEnableScheduling() {
    var search = search(10, false);
    useFakeSource();
    service.collect(search.id(), false);
    var saved = searches.findById(search.id());
    assertThat(saved.enabled()).isFalse();
    assertThat(saved.nextRunAt()).isNull();
    assertThat(runs.findAll(null, null, 0, 20).content()).hasSize(1);
  }

  @Test
  void skipsDisabledDeletedAndNotDueAutomaticJobs() {
    var disabled = search(10, false);
    var future = search(10, true);
    jdbc.update(
        "UPDATE search_queries SET next_run_at = ? WHERE id = ?",
        clock.instant().plusSeconds(600).toString(),
        future.id().toString());
    service.collect(disabled.id(), true);
    service.collect(future.id(), true);
    service.collect(UUID.randomUUID(), true);
    verifyNoInteractions(source);
    assertThat(runs.findAll(null, null, 0, 20).content()).isEmpty();
  }

  @Test
  void disablingDuringFetchAllowsCompletionWithoutAnotherAutomaticRun() {
    var search = search(10, true);
    when(source.source()).thenReturn(FAKE);
    when(source.fetch(any()))
        .thenAnswer(
            invocation -> {
              searches.update(search.id(), new SearchQueryRequest(JAVA, JAVA, 10, false));
              return new FakeVacancySource().fetch(invocation.getArgument(0));
            });
    service.collect(search.id(), true);
    service.collect(search.id(), true);
    assertThat(runs.findAll(null, null, 0, 20).content()).hasSize(1);
    assertThat(searches.findById(search.id()).nextRunAt()).isNull();
    assertThat(jdbc.queryForObject(VACANCY_COUNT, Integer.class)).isEqualTo(2);
  }

  @Test
  void deletionDuringFetchKeepsVacanciesAndDetachedHistory() {
    var search = search(10, true);
    when(source.source()).thenReturn(FAKE);
    when(source.fetch(any()))
        .thenAnswer(
            invocation -> {
              searches.delete(search.id());
              return new FakeVacancySource().fetch(invocation.getArgument(0));
            });
    service.collect(search.id(), true);
    var completed = runs.findAll(null, null, 0, 20).content().getFirst();
    assertThat(completed.searchQueryId()).isNull();
    assertThat(completed.searchQueryName()).isEqualTo(JAVA);
    assertThat(completed.status()).isEqualTo(CollectionRunStatus.SUCCESS);
    assertThat(jdbc.queryForObject(VACANCY_COUNT, Integer.class)).isEqualTo(2);
  }

  @Test
  void sourceFailureIsSanitizedAndDoesNotPreventOtherJobsOrDelayedRetry() {
    var failed = search(10, true);
    var other = search(30, true);
    when(source.source()).thenReturn(FAKE);
    when(source.fetch(any()))
        .thenThrow(new IllegalStateException("password=secret-token\nprivate contents"));
    service.collect(failed.id(), true);
    var result = runs.findAll(failed.id(), null, 0, 20).content().getFirst();
    assertThat(result.status()).isEqualTo(CollectionRunStatus.FAILED);
    assertThat(result.errorMessage()).isEqualTo("Collection failed (IllegalStateException)");
    assertThat(result.fetchedCount()).isZero();
    assertThat(searches.findById(failed.id()).nextRunAt())
        .isEqualTo(clock.instant().plusSeconds(600));
    useFakeSource();
    service.collect(failed.id(), true);
    assertThat(runs.findAll(failed.id(), null, 0, 20).content()).hasSize(1);
    service.collect(other.id(), true);
    clock.set(clock.instant().plusSeconds(600));
    service.collect(failed.id(), true);
    assertThat(runs.findAll(failed.id(), CollectionRunStatus.SUCCESS, 0, 20).content()).hasSize(1);
    assertThat(runs.findAll(other.id(), CollectionRunStatus.SUCCESS, 0, 20).content()).hasSize(1);
  }

  @Test
  void invalidBatchProducesFailedHistoryWithFetchedCountAndNoPartialIngestion() {
    var search = search(10, true);
    var valid = new FakeVacancySource().fetch(search).getFirst();
    var invalid =
        new VacancyData(
            FAKE,
            "broken",
            "",
            "Employer",
            "Remote",
            null,
            null,
            null,
            "https://example.invalid/3",
            null);
    when(source.source()).thenReturn(FAKE);
    when(source.fetch(any())).thenReturn(List.of(valid, invalid));
    service.collect(search.id(), false);
    var run = runs.findAll(null, null, 0, 20).content().getFirst();
    assertThat(run.status()).isEqualTo(CollectionRunStatus.FAILED);
    assertThat(run.fetchedCount()).isEqualTo(2);
    assertThat(run.createdCount()).isZero();
    assertThat(jdbc.queryForObject(VACANCY_COUNT, Integer.class)).isZero();
  }

  @Test
  void startupInterruptsUnfinishedRunsAndSchedulesDelayedRecovery() {
    var search = search(10, true);
    var run =
        new CollectionRun(
            UUID.randomUUID(),
            search.id(),
            JAVA,
            FAKE,
            CollectionRunStatus.RUNNING,
            clock.instant(),
            null,
            0,
            0,
            0,
            null);
    runs.insert(run);
    clock.set(clock.instant().plusSeconds(60));
    service.afterSingletonsInstantiated();
    assertThat(runs.findById(run.id()).orElseThrow().status())
        .isEqualTo(CollectionRunStatus.INTERRUPTED);
    assertThat(searches.findById(search.id()).nextRunAt())
        .isEqualTo(clock.instant().plusSeconds(600));
    var scheduled = searches.findById(search.id()).nextRunAt();
    service.afterSingletonsInstantiated();
    assertThat(searches.findById(search.id()).nextRunAt()).isEqualTo(scheduled);
    useFakeSource();
    clock.set(scheduled);
    service.collect(search.id(), true);
    assertThat(runs.findAll(search.id(), null, 0, 20).content()).hasSize(2);
    assertThat(runs.findAll(search.id(), CollectionRunStatus.SUCCESS, 0, 20).content()).hasSize(1);
  }

  @Test
  void databaseFailureRollsBackVacanciesMatchesAndSuccessStatistics() {
    var search = search(10, true);
    useFakeSource();
    jdbc.execute(
        """
        CREATE TRIGGER reject_fixture BEFORE INSERT ON vacancies
        WHEN NEW.external_id = '2' BEGIN SELECT RAISE(ABORT, 'Rejected fixture'); END
        """);
    try {
      service.collect(search.id(), false);
      var run = runs.findAll(null, null, 0, 20).content().getFirst();
      assertThat(run.status()).isEqualTo(CollectionRunStatus.FAILED);
      assertThat(run.fetchedCount()).isEqualTo(3);
      assertThat(run.createdCount()).isZero();
      assertThat(run.updatedCount()).isZero();
      assertThat(jdbc.queryForObject(VACANCY_COUNT, Integer.class)).isZero();
      assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM vacancy_search_matches", Integer.class))
          .isZero();
      assertThat(searches.findById(search.id()).nextRunAt())
          .isEqualTo(clock.instant().plusSeconds(600));
    } finally {
      jdbc.execute("DROP TRIGGER reject_fixture");
    }
  }

  private SearchQuery search(int interval, boolean enabled) {
    return searches.create(new SearchQueryRequest(JAVA, JAVA, interval, enabled));
  }

  private void useFakeSource() {
    when(source.source()).thenReturn(FAKE);
    doAnswer(invocation -> new FakeVacancySource().fetch(invocation.getArgument(0)))
        .when(source)
        .fetch(any());
  }
}
