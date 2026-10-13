package com.ororura.jobmonitor.vacancy;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

class VacancyOptionalFieldsMigrationTest {
  @Test
  void upgradesV4WithoutLosingVacanciesStatusesMatchesOrForeignKeys(@TempDir Path directory)
      throws Exception {
    try (var connection =
            DriverManager.getConnection("jdbc:sqlite:" + directory.resolve("upgrade.db"));
        var dataSource = new SingleConnectionDataSource(connection, true)) {
      try (var statement = connection.createStatement()) {
        statement.execute("PRAGMA foreign_keys = ON");
      }
      Flyway.configure().dataSource(dataSource).target("4").load().migrate();
      var jdbc = new JdbcTemplate(dataSource);
      UUID searchId = UUID.randomUUID();
      Instant now = Instant.parse("2026-10-13T09:00:00Z");
      jdbc.update(
          "INSERT INTO search_queries VALUES (?, 'Java', 'Java', 10, 1, ?, ?, NULL)",
          searchId.toString(),
          now.toString(),
          now.toString());
      var repository = new VacancyRepository(jdbc);
      var data =
          new VacancyData(
              "FAKE",
              "1",
              "Java",
              "Employer",
              "Remote",
              null,
              null,
              null,
              "http://example.invalid/vacancies/1",
              now);
      var vacancy = repository.upsert(data, now, List.of(searchId));
      repository.updateStatus(vacancy.id(), VacancyStatus.APPLIED);
      var beforeVacancies = jdbc.queryForList("SELECT * FROM vacancies");
      var beforeMatches = jdbc.queryForList("SELECT * FROM vacancy_search_matches");
      var latest = Flyway.configure().dataSource(dataSource).load();
      assertThat(latest.migrate().migrationsExecuted).isEqualTo(2);
      assertThat(jdbc.queryForList("SELECT * FROM vacancies")).isEqualTo(beforeVacancies);
      assertThat(jdbc.queryForList("SELECT * FROM vacancy_search_matches"))
          .isEqualTo(beforeMatches);
      assertThat(jdbc.queryForList("PRAGMA foreign_key_check")).isEmpty();
      assertThat(jdbc.queryForObject("PRAGMA foreign_keys", Integer.class)).isEqualTo(1);
      assertThat(jdbc.queryForObject("SELECT source FROM search_queries", String.class))
          .isEqualTo("FAKE");
      var unknown =
          new VacancyData("FAKE", "1", "Java", null, null, null, null, null, data.url(), null);
      var updated = repository.upsert(unknown, now.plusSeconds(1), List.of(searchId));
      assertThat(updated.employerName()).isNull();
      assertThat(updated.area()).isNull();
      assertThat(updated.status()).isEqualTo(VacancyStatus.APPLIED);
      assertThat(updated.id()).isEqualTo(vacancy.id());
      jdbc.update("DELETE FROM search_queries WHERE id = ?", searchId.toString());
      assertThat(jdbc.queryForList("SELECT * FROM vacancy_search_matches")).isEmpty();
      assertThat(repository.findById(vacancy.id())).contains(updated);
      assertThat(latest.migrate().migrationsExecuted).isZero();
    }
  }
}
