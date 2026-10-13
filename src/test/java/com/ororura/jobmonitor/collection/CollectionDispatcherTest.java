package com.ororura.jobmonitor.collection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.ororura.jobmonitor.search.SearchNotFoundException;
import com.ororura.jobmonitor.search.SearchService;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.core.task.TaskExecutor;
import org.springframework.core.task.TaskRejectedException;

final class CollectionDispatcherTest {
  private final SearchService searches = mock(SearchService.class);
  private final CollectionService service = mock(CollectionService.class);
  private final List<Runnable> tasks = new ArrayList<>();
  private final CollectionDispatcher dispatcher =
      new CollectionDispatcher(searches, service, tasks::add);

  @Test
  void manualAcceptanceIsAsynchronousAndRejectsQueuedOrRunningDuplicates() {
    UUID id = UUID.randomUUID();
    dispatcher.runManually(id);
    assertThat(tasks).hasSize(1);
    verifyNoInteractions(service);
    assertThatThrownBy(() -> dispatcher.runManually(id))
        .isInstanceOf(CollectionConflictException.class);
    assertThat(dispatcher.submit(id, true)).isFalse();
    doAnswer(
            invocation -> {
              assertThatThrownBy(() -> dispatcher.runManually(id))
                  .isInstanceOf(CollectionConflictException.class);
              return null;
            })
        .when(service)
        .collect(id, false);
    tasks.removeFirst().run();
    assertThat(dispatcher.submit(id, true)).isTrue();
    tasks.removeFirst().run();
    verify(service).collect(id, false);
    verify(service).collect(id, true);
  }

  @Test
  void repeatedPollsDoNotAccumulateDuplicateQueueEntries() {
    UUID id = UUID.randomUUID();
    assertThat(dispatcher.submit(id, true)).isTrue();
    for (int i = 0; i < 100; i++) {
      assertThat(dispatcher.submit(id, true)).isFalse();
    }
    assertThat(tasks).hasSize(1);
    tasks.getFirst().run();
    assertThat(dispatcher.submit(id, true)).isTrue();
  }

  @Test
  void rejectsMissingManualSearchBeforeQueuing() {
    UUID id = UUID.randomUUID();
    doThrow(new SearchNotFoundException(id)).when(searches).findById(id);
    assertThatThrownBy(() -> dispatcher.runManually(id))
        .isInstanceOf(SearchNotFoundException.class);
    assertThat(tasks).isEmpty();
  }

  @Test
  void rejectedSubmissionReleasesIdentityForLaterRetry() {
    TaskExecutor worker = mock(TaskExecutor.class);
    doThrow(new TaskRejectedException("Full"))
        .doAnswer(
            invocation -> {
              tasks.add(invocation.getArgument(0));
              return null;
            })
        .when(worker)
        .execute(any());
    var bounded = new CollectionDispatcher(searches, service, worker);
    UUID id = UUID.randomUUID();
    assertThatThrownBy(() -> bounded.runManually(id))
        .isInstanceOf(CollectionConflictException.class);
    bounded.runManually(id);
    assertThat(tasks).hasSize(1);
  }

  @Test
  void infrastructureFailureReleasesIdentityAndDoesNotStopOtherJobs() {
    UUID first = UUID.randomUUID();
    UUID second = UUID.randomUUID();
    doThrow(new IllegalStateException("private error")).when(service).collect(first, true);
    dispatcher.submit(first, true);
    dispatcher.submit(second, true);
    tasks.removeFirst().run();
    tasks.removeFirst().run();
    verify(service).collect(second, true);
    assertThat(dispatcher.submit(first, true)).isTrue();
  }

  @Test
  void realWorkerSerializesJobsAndBoundsQueueCapacity() throws InterruptedException {
    var worker =
        new CollectionConfiguration()
            .collectionWorker(new CollectionProperties(false, Duration.ofSeconds(30), 1));
    worker.initialize();
    var bounded = new CollectionDispatcher(searches, service, worker);
    var started = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var finished = new CountDownLatch(1);
    UUID first = UUID.randomUUID();
    UUID second = UUID.randomUUID();
    UUID rejected = UUID.randomUUID();
    doAnswer(
            invocation -> {
              started.countDown();
              assertThat(release.await(5, TimeUnit.SECONDS)).isTrue();
              return null;
            })
        .when(service)
        .collect(first, false);
    doAnswer(
            invocation -> {
              assertThat(release.getCount()).isZero();
              finished.countDown();
              return null;
            })
        .when(service)
        .collect(eq(second), eq(false));
    try {
      bounded.runManually(first);
      assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
      assertThatThrownBy(() -> bounded.runManually(first))
          .isInstanceOf(CollectionConflictException.class);
      bounded.runManually(second);
      assertThatThrownBy(() -> bounded.runManually(second))
          .isInstanceOf(CollectionConflictException.class);
      assertThatThrownBy(() -> bounded.runManually(rejected))
          .isInstanceOf(CollectionConflictException.class);
      verify(service, times(0)).collect(second, false);
      assertThat(worker.getThreadPoolExecutor().getQueue()).hasSize(1);
      release.countDown();
      assertThat(finished.await(5, TimeUnit.SECONDS)).isTrue();
    } finally {
      release.countDown();
      worker.shutdown();
    }
  }
}
