package com.ororura.jobmonitor.search;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
import org.springframework.jdbc.UncategorizedSQLException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class SearchRepositoryTest {

  private static final String CHECK_CONSTRAINT = "SQLITE_CONSTRAINT_CHECK";

  private static final String JAVA = "Java";

  @TempDir static Path temporaryDirectory;

  @Autowired private SearchRepository repository;
  @Autowired private JdbcTemplate jdbcTemplate;

  @DynamicPropertySource
  static void databaseDirectory(DynamicPropertyRegistry registry) {
    registry.add("job-monitor.data-directory", () -> temporaryDirectory.toString());
  }

  @BeforeEach
  void clearQueries() {
    jdbcTemplate.update("DELETE FROM search_queries");
  }

  @Test
  void persistsAllFieldsInSqliteAndReadsThemThroughANewConnection() throws Exception {
    SearchQuery query = query(JAVA, "  (Java AND \"Spring Boot\") OR NOT ('senior')  ", 10);
    repository.insert(query);

    assertThat(repository.findById(query.id())).contains(query);
    assertThat(repository.findAll()).containsExactly(query);
    try (var connection =
            DriverManager.getConnection(
                "jdbc:sqlite:" + temporaryDirectory.resolve("job-monitor.db"));
        var statement = connection.prepareStatement("SELECT * FROM search_queries WHERE id = ?")) {
      statement.setString(1, query.id().toString());
      try (var result = statement.executeQuery()) {
        assertThat(result.next()).isTrue();
        assertThat(result.getString("name")).isEqualTo(query.name());
        assertThat(result.getString("query_text")).isEqualTo(query.queryText());
        assertThat(result.getInt("interval_minutes")).isEqualTo(10);
        assertThat(result.getInt("enabled")).isEqualTo(1);
        assertThat(result.getString("created_at")).isEqualTo(query.createdAt().toString());
        assertThat(result.getString("updated_at")).isEqualTo(query.updatedAt().toString());
        assertThat(result.getString("next_run_at")).isEqualTo(query.nextRunAt().toString());
      }
    }
  }

  @Test
  void updatesDeletesAndHandlesMissingRows() {
    SearchQuery initial = query(JAVA, JAVA, 10);
    repository.insert(initial);
    SearchQuery updated =
        new SearchQuery(
            initial.id(),
            "Kotlin",
            "Kotlin AND NOT Java",
            60,
            false,
            initial.createdAt(),
            initial.updatedAt().plusSeconds(1),
            null);

    assertThat(repository.update(updated)).isTrue();
    assertThat(repository.findById(initial.id())).contains(updated);
    assertThat(repository.deleteById(initial.id())).isTrue();
    assertThat(repository.findById(initial.id())).isEmpty();
    assertThat(repository.findAll()).isEmpty();
    assertThat(repository.update(updated)).isFalse();
    assertThat(repository.deleteById(initial.id())).isFalse();
  }

  @Test
  void databaseEnforcesUniqueIdsAndRequiredValidFields() {
    SearchQuery query = query(JAVA, JAVA, 10);
    repository.insert(query);

    assertThatThrownBy(() -> repository.insert(query))
        .isInstanceOf(UncategorizedSQLException.class)
        .hasMessageContaining("SQLITE_CONSTRAINT_PRIMARYKEY");
    assertThatThrownBy(() -> repository.insert(query(JAVA, JAVA, 9)))
        .isInstanceOf(UncategorizedSQLException.class)
        .hasMessageContaining(CHECK_CONSTRAINT);
    assertThatThrownBy(() -> repository.insert(query(" ", JAVA, 10)))
        .isInstanceOf(UncategorizedSQLException.class)
        .hasMessageContaining(CHECK_CONSTRAINT);
    assertThatThrownBy(() -> repository.insert(query(JAVA, "", 10)))
        .isInstanceOf(UncategorizedSQLException.class)
        .hasMessageContaining(CHECK_CONSTRAINT);
    assertThatThrownBy(() -> repository.insert(query(null, JAVA, 10)))
        .isInstanceOf(UncategorizedSQLException.class)
        .hasMessageContaining("SQLITE_CONSTRAINT_NOTNULL");
    assertThatThrownBy(() -> jdbcTemplate.update("UPDATE search_queries SET enabled = ?", 2))
        .isInstanceOf(UncategorizedSQLException.class)
        .hasMessageContaining(CHECK_CONSTRAINT);
    assertThat(repository.findAll()).containsExactly(query);
  }

  @Test
  void upgradesExistingV1DatabaseWithoutLosingData() {
    var dataSource =
        new DriverManagerDataSource("jdbc:sqlite:" + temporaryDirectory.resolve("upgrade.db"));
    Flyway.configure().dataSource(dataSource).target("1").load().migrate();
    var existingDatabase = new JdbcTemplate(dataSource);
    existingDatabase.update(
        "INSERT INTO application_metadata (key, value) VALUES (?, ?)", "existing", "preserved");

    Flyway flyway = Flyway.configure().dataSource(dataSource).load();
    assertThat(flyway.migrate().migrationsExecuted).isEqualTo(1);
    assertThat(
            existingDatabase.queryForObject(
                "SELECT value FROM application_metadata WHERE key = ?", String.class, "existing"))
        .isEqualTo("preserved");
    assertThat(
            existingDatabase.queryForObject("SELECT COUNT(*) FROM search_queries", Integer.class))
        .isZero();
    assertThat(flyway.migrate().migrationsExecuted).isZero();
  }

  private static SearchQuery query(String name, String queryText, int intervalMinutes) {
    Instant now = Instant.parse("2026-10-13T10:00:00.123456789Z");
    return new SearchQuery(
        UUID.randomUUID(),
        name,
        queryText,
        intervalMinutes,
        true,
        now,
        now.plusSeconds(60),
        now.plusSeconds(600));
  }
}
