package com.ororura.jobmonitor.collection;

import static org.assertj.core.api.Assertions.assertThat;

import com.ororura.jobmonitor.JobMonitorApplication;
import com.ororura.jobmonitor.search.SearchQueryRequest;
import com.ororura.jobmonitor.search.SearchService;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

class CollectionRestartTest {
  @Test
  void reopeningSameSqliteDatabaseInterruptsRunningHistoryAndResumesWork(@TempDir Path directory) {
    UUID searchId;
    UUID unfinishedId = UUID.randomUUID();
    UUID successId;
    try (var first = open(directory)) {
      var searches = first.getBean(SearchService.class);
      var search = searches.create(new SearchQueryRequest("Restart", "Java", 10, true));
      searchId = search.id();
      first.getBean(CollectionService.class).collect(searchId, false);
      var runs = first.getBean(CollectionRunRepository.class);
      successId = runs.findAll(searchId, null, 0, 20).content().getFirst().id();
      runs.insert(
          new CollectionRun(
              unfinishedId,
              searchId,
              search.name(),
              "FAKE",
              CollectionRunStatus.RUNNING,
              CollectionTestClock.START,
              null,
              0,
              0,
              0,
              null));
    }
    try (var reopened = open(directory)) {
      var runs = reopened.getBean(CollectionRunRepository.class);
      assertThat(runs.findById(unfinishedId).orElseThrow().status())
          .isEqualTo(CollectionRunStatus.INTERRUPTED);
      assertThat(runs.findById(successId).orElseThrow().status())
          .isEqualTo(CollectionRunStatus.SUCCESS);
      var clock = reopened.getBean(CollectionTestClock.class);
      var searches = reopened.getBean(SearchService.class);
      assertThat(searches.findById(searchId).nextRunAt())
          .isEqualTo(clock.instant().plusSeconds(600));
      clock.set(searches.findById(searchId).nextRunAt());
      reopened.getBean(CollectionService.class).collect(searchId, true);
      assertThat(runs.findAll(searchId, CollectionRunStatus.SUCCESS, 0, 20).content()).hasSize(2);
      assertThat(runs.findAll(searchId, CollectionRunStatus.RUNNING, 0, 20).content()).isEmpty();
    }
  }

  private ConfigurableApplicationContext open(Path directory) {
    return new SpringApplicationBuilder(
            JobMonitorApplication.class, CollectionTestConfiguration.class)
        .web(WebApplicationType.NONE)
        .run(
            "--job-monitor.data-directory=" + directory,
            "--app.collection.poll-interval=1h",
            "--spring.main.banner-mode=off");
  }
}
