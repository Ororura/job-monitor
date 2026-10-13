package com.ororura.jobmonitor;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class JobMonitorApplicationTest {

  @TempDir static Path temporaryDirectory;

  @DynamicPropertySource
  static void databaseDirectory(DynamicPropertyRegistry registry) {
    registry.add(
        "job-monitor.data-directory",
        () -> temporaryDirectory.resolve("database files").toString());
  }

  @Test
  void startsWithSqliteAndAppliesMigration(
      @Autowired JdbcTemplate jdbcTemplate, @Autowired Flyway flyway) {
    assertThat(temporaryDirectory.resolve("database files/job-monitor.db")).isRegularFile();
    assertThat(flyway.info().current().getVersion().getVersion()).isEqualTo("2");
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT value FROM application_metadata WHERE key = ?",
                String.class,
                "application"))
        .isEqualTo("job-monitor");

    jdbcTemplate.update(
        "INSERT INTO application_metadata (key, value) VALUES (?, ?)", "test", "SQLite works");
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT value FROM application_metadata WHERE key = ?", String.class, "test"))
        .isEqualTo("SQLite works");
    assertThat(jdbcTemplate.queryForObject("PRAGMA foreign_keys", Integer.class)).isEqualTo(1);
    assertThat(jdbcTemplate.queryForObject("PRAGMA busy_timeout", Integer.class)).isEqualTo(5000);

    assertThat(flyway.migrate().migrationsExecuted).isZero();
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM flyway_schema_history WHERE success = 1", Integer.class))
        .isEqualTo(2);
    jdbcTemplate.update("DELETE FROM application_metadata WHERE key = ?", "test");
  }
}
