package com.ororura.jobmonitor.collection;

import com.ororura.jobmonitor.search.SearchQuery;
import com.ororura.jobmonitor.search.SearchRepository;
import com.ororura.jobmonitor.source.VacancySource;
import com.ororura.jobmonitor.vacancy.VacancyData;
import com.ororura.jobmonitor.vacancy.VacancyService;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.scheduling.support.DelegatingErrorHandlingRunnable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class CollectionService implements SmartInitializingSingleton {
  private final SearchRepository searches;
  private final CollectionRunRepository runs;
  private final VacancySource source;
  private final VacancyService vacancies;
  private final Clock clock;
  private final TransactionTemplate transaction;

  CollectionService(
      SearchRepository searches,
      CollectionRunRepository runs,
      VacancySource source,
      VacancyService vacancies,
      Clock clock,
      PlatformTransactionManager transactionManager) {
    this.searches = searches;
    this.runs = runs;
    this.source = source;
    this.vacancies = vacancies;
    this.clock = clock;
    this.transaction = new TransactionTemplate(transactionManager);
  }

  @Override
  public void afterSingletonsInstantiated() {
    transaction.executeWithoutResult(
        status -> {
          Instant now = clock.instant();
          List<CollectionRun> unfinished = runs.findRunning();
          runs.interruptRunning(now);
          for (CollectionRun run : unfinished) {
            if (run.searchQueryId() != null) {
              scheduleNext(run.searchQueryId(), now);
            }
          }
        });
  }

  public CollectionRun findById(UUID id) {
    return runs.findById(id).orElseThrow(() -> new CollectionRunNotFoundException(id));
  }

  public CollectionRunPage findAll(UUID searchId, CollectionRunStatus status, int page, int size) {
    if (page < 0 || size < 1 || size > 100) {
      throw new CollectionValidationException(
          "Page must be non-negative and size must be 1 to 100");
    }
    return runs.findAll(searchId, status, page, size);
  }

  void collect(UUID searchId, boolean automatic) {
    StartedCollection started = transaction.execute(status -> start(searchId, automatic));
    if (started == null) {
      return;
    }
    var fetched = new AtomicInteger();
    new DelegatingErrorHandlingRunnable(
            () -> {
              // Source access must never hold the single database connection or a transaction.
              List<VacancyData> data = source.fetch(started.query());
              fetched.set(data.size());
              transaction.executeWithoutResult(
                  status -> {
                    // A search may be deleted while fetching. Keep the vacancies and the detached
                    // history.
                    UUID matchId = searches.findById(searchId).isPresent() ? searchId : null;
                    var result = vacancies.ingest(data, matchId);
                    Instant finishedAt = clock.instant();
                    runs.finish(
                        started.run().id(),
                        CollectionRunStatus.SUCCESS,
                        finishedAt,
                        data.size(),
                        result.createdCount(),
                        result.updatedCount(),
                        null);
                    scheduleNext(searchId, finishedAt);
                  });
            },
            exception -> {
              if (exception instanceof Error fatal) {
                throw fatal;
              }
              // Isolate failures at the job boundary. Never persist source messages containing
              // secrets.
              int fetchedCount = fetched.get();
              transaction.executeWithoutResult(
                  status -> {
                    Instant finishedAt = clock.instant();
                    runs.finish(
                        started.run().id(),
                        CollectionRunStatus.FAILED,
                        finishedAt,
                        fetchedCount,
                        0,
                        0,
                        "Collection failed (" + exception.getClass().getSimpleName() + ")");
                    scheduleNext(searchId, finishedAt);
                  });
            })
        .run();
  }

  private StartedCollection start(UUID searchId, boolean automatic) {
    SearchQuery query = searches.findById(searchId).orElse(null);
    Instant now = clock.instant();
    if (query == null || (automatic && !isDue(query, now))) {
      return null;
    }
    var run =
        new CollectionRun(
            UUID.randomUUID(),
            query.id(),
            query.name(),
            source.source(),
            CollectionRunStatus.RUNNING,
            now,
            null,
            0,
            0,
            0,
            null);
    runs.insert(run);
    return new StartedCollection(query, run);
  }

  private void scheduleNext(UUID searchId, Instant finishedAt) {
    searches
        .findById(searchId)
        .ifPresent(
            query ->
                searches.updateNextRunAt(
                    searchId,
                    query.enabled()
                        ? finishedAt.plus(query.intervalMinutes(), ChronoUnit.MINUTES)
                        : null));
  }

  static boolean isDue(SearchQuery query, Instant now) {
    return query.enabled() && (query.nextRunAt() == null || !query.nextRunAt().isAfter(now));
  }

  private record StartedCollection(SearchQuery query, CollectionRun run) {}
}
