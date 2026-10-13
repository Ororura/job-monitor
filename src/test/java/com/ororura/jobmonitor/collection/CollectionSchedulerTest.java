package com.ororura.jobmonitor.collection;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.ororura.jobmonitor.search.SearchQuery;
import com.ororura.jobmonitor.search.SearchService;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CollectionSchedulerTest {
  private final SearchService searches = mock(SearchService.class);
  private final CollectionDispatcher dispatcher = mock(CollectionDispatcher.class);
  private final CollectionTestClock clock = new CollectionTestClock();

  @Test
  void firstRunNullAndDueBoundaryAreAcceptedButFutureAndDisabledAreSkipped() {
    var first = query(true, null);
    var due = query(true, clock.instant());
    var future = query(true, clock.instant().plusNanos(1));
    var disabled = query(false, null);
    when(searches.findAll()).thenReturn(List.of(first, due, future, disabled));
    scheduler(true).poll();
    verify(dispatcher).submit(first.id(), true);
    verify(dispatcher).submit(due.id(), true);
    verify(dispatcher, never()).submit(future.id(), true);
    verify(dispatcher, never()).submit(disabled.id(), true);
  }

  @Test
  void pollUsesManagedClockAndFreshSearchState() {
    var future = query(true, clock.instant().plusSeconds(600));
    when(searches.findAll()).thenReturn(List.of(future));
    var scheduler = scheduler(true);
    scheduler.poll();
    verifyNoInteractions(dispatcher);
    clock.set(future.nextRunAt());
    scheduler.poll();
    verify(dispatcher).submit(future.id(), true);
    when(searches.findAll()).thenReturn(List.of());
    scheduler.poll();
    verify(dispatcher).submit(future.id(), true);
  }

  @Test
  void automaticCollectionIsDisabledByConfiguration() {
    scheduler(false).poll();
    verifyNoInteractions(searches, dispatcher);
  }

  private CollectionScheduler scheduler(boolean enabled) {
    return new CollectionScheduler(
        searches, dispatcher, new CollectionProperties(enabled, Duration.ofSeconds(30), 2), clock);
  }

  private SearchQuery query(boolean enabled, Instant nextRun) {
    return new SearchQuery(
        UUID.randomUUID(), "Java", "Java", 10, enabled, clock.instant(), clock.instant(), nextRun);
  }
}
