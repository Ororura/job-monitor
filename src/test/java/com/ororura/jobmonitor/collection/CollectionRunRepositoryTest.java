package com.ororura.jobmonitor.collection;

import static org.assertj.core.api.Assertions.assertThat;

import com.ororura.jobmonitor.search.SearchQueryRequest;
import com.ororura.jobmonitor.search.SearchService;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.Instant;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class CollectionRunRepositoryTest {
  private static final String ORIGINAL = "Original";
  private static final String JAVA = "Java";
  private static final Instant NOW = Instant.parse("2026-10-13T10:00:00.123456789Z");
  @TempDir static Path directory;
  @Autowired private CollectionRunRepository repository;
  @Autowired private SearchService searches;
  @Autowired private JdbcTemplate jdbc;

  @DynamicPropertySource
  static void database(DynamicPropertyRegistry registry) {
    registry.add("job-monitor.data-directory", () -> directory.toString());
  }

  @BeforeEach
  void clear() {
    jdbc.update("DELETE FROM collection_runs");
    jdbc.update("DELETE FROM search_queries");
  }

  @Test
  void persistsUtcStatisticsAndRetainsSnapshotAfterDeletion() throws Exception {
    var search = searches.create(new SearchQueryRequest(ORIGINAL, JAVA, 30, true));
    var run = running(search.id(), NOW);
    repository.insert(run);
    assertThat(repository.findById(run.id())).contains(run);
    searches.update(search.id(), new SearchQueryRequest("Renamed", JAVA, 30, true));
    repository.finish(run.id(), CollectionRunStatus.SUCCESS, NOW.plusSeconds(45), 3, 2, 0, null);
    searches.delete(search.id());
    var saved = repository.findById(run.id()).orElseThrow();
    assertThat(saved.searchQueryId()).isNull();
    assertThat(saved.searchQueryName()).isEqualTo(ORIGINAL);
    assertThat(saved.status()).isEqualTo(CollectionRunStatus.SUCCESS);
    assertThat(saved.fetchedCount()).isEqualTo(3);
    assertThat(saved.createdCount()).isEqualTo(2);
    assertThat(saved.updatedCount()).isZero();
    assertThat(saved.finishedAt()).isEqualTo(NOW.plusSeconds(45));
    try (var connection =
            DriverManager.getConnection("jdbc:sqlite:" + directory.resolve("job-monitor.db"));
        var statement =
            connection.prepareStatement("SELECT started_at FROM collection_runs WHERE id = ?")) {
      statement.setString(1, run.id().toString());
      try (var result = statement.executeQuery()) {
        assertThat(result.next()).isTrue();
        assertThat(result.getString(1)).isEqualTo("2026-10-13T10:00:00.123456789Z");
      }
    }
  }

  @Test
  void filtersAndPaginatesWithChronologicalFractionalOrdering() {
    var first = searches.create(new SearchQueryRequest(ORIGINAL, JAVA, 10, true));
    var second = searches.create(new SearchQueryRequest(ORIGINAL, JAVA, 20, true));
    var older = running(first.id(), NOW);
    var newer = running(first.id(), NOW.plusNanos(1));
    var other = running(second.id(), NOW.plusSeconds(1));
    repository.insert(older);
    repository.insert(newer);
    repository.insert(other);
    repository.finish(
        older.id(), CollectionRunStatus.FAILED, NOW.plusSeconds(2), 0, 0, 0, "Source failed");
    assertThat(repository.findAll(null, null, 0, 1).content()).containsExactly(other);
    assertThat(repository.findAll(first.id(), null, 0, 20).content())
        .extracting(CollectionRun::id)
        .containsExactly(newer.id(), older.id());
    assertThat(repository.findAll(first.id(), CollectionRunStatus.RUNNING, 0, 20).content())
        .containsExactly(newer);
    assertThat(repository.findAll(null, null, 1, 1).content()).containsExactly(newer);
    assertThat(repository.findAll(null, null, 3, 1).content()).isEmpty();
    assertThat(repository.findAll(UUID.randomUUID(), null, 0, 20).totalElements()).isZero();
  }

  @Test
  void interruptionIsIdempotentAndDoesNotChangeFinishedRuns() {
    var search = searches.create(new SearchQueryRequest(ORIGINAL, JAVA, 30, true));
    var unfinished = running(search.id(), NOW);
    var finished = running(search.id(), NOW.plusNanos(1));
    repository.insert(unfinished);
    repository.insert(finished);
    repository.finish(
        finished.id(), CollectionRunStatus.SUCCESS, NOW.plusSeconds(60), 3, 2, 0, null);
    assertThat(repository.interruptRunning(NOW.plusSeconds(120))).isEqualTo(1);
    assertThat(repository.interruptRunning(NOW.plusSeconds(180))).isZero();
    assertThat(repository.findRunning()).isEmpty();
    assertThat(repository.findById(unfinished.id()).orElseThrow().status())
        .isEqualTo(CollectionRunStatus.INTERRUPTED);
    assertThat(repository.findById(unfinished.id()).orElseThrow().finishedAt())
        .isEqualTo(NOW.plusSeconds(120));
    assertThat(repository.findById(finished.id()).orElseThrow().status())
        .isEqualTo(CollectionRunStatus.SUCCESS);
  }

  @Test
  void upgradesV3WithoutLosingData(@TempDir Path upgradeDirectory) {
    var dataSource =
        new DriverManagerDataSource("jdbc:sqlite:" + upgradeDirectory.resolve("upgrade.db"));
    Flyway.configure().dataSource(dataSource).target("3").load().migrate();
    var database = new JdbcTemplate(dataSource);
    database.update("INSERT INTO application_metadata (key, value) VALUES ('preserved', 'yes')");
    Flyway latest = Flyway.configure().dataSource(dataSource).load();
    assertThat(latest.migrate().migrationsExecuted).isEqualTo(3);
    assertThat(
            database.queryForObject(
                "SELECT value FROM application_metadata WHERE key = 'preserved'", String.class))
        .isEqualTo("yes");
    assertThat(database.queryForObject("SELECT COUNT(*) FROM collection_runs", Integer.class))
        .isZero();
    assertThat(latest.migrate().migrationsExecuted).isZero();
  }

  private static CollectionRun running(UUID searchId, Instant start) {
    return new CollectionRun(
        UUID.randomUUID(),
        searchId,
        ORIGINAL,
        "FAKE",
        CollectionRunStatus.RUNNING,
        start,
        null,
        0,
        0,
        0,
        null);
  }
}
