package com.ororura.jobmonitor.vacancy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ororura.jobmonitor.search.SearchNotFoundException;
import com.ororura.jobmonitor.search.SearchQuery;
import com.ororura.jobmonitor.search.SearchQueryRequest;
import com.ororura.jobmonitor.search.SearchService;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class VacancyRepositoryTest {

  private static final Instant NOW = Instant.parse("2026-10-13T10:00:00Z");
  private static final String HH = "HH";
  private static final String MATCH_COUNT = "SELECT COUNT(*) FROM vacancy_search_matches";
  private static final String VACANCY_COUNT = "SELECT COUNT(*) FROM vacancies";

  @TempDir static Path temporaryDirectory;

  @Autowired private VacancyRepository repository;
  @Autowired private VacancyService service;
  @Autowired private SearchService searchService;
  @Autowired private JdbcTemplate jdbcTemplate;

  @DynamicPropertySource
  static void databaseDirectory(DynamicPropertyRegistry registry) {
    registry.add("job-monitor.data-directory", () -> temporaryDirectory.toString());
  }

  @BeforeEach
  void clearData() {
    jdbcTemplate.update("DELETE FROM vacancies");
    jdbcTemplate.update("DELETE FROM search_queries");
  }

  @Test
  void persistsEveryFieldAndExactDecimalThroughAnIndependentConnection() throws Exception {
    VacancyData data = data(HH, "1");
    Vacancy vacancy = repository.upsert(data, NOW, List.of());

    assertThat(vacancy.id()).isNotNull();
    assertThat(vacancy.source()).isEqualTo(data.source());
    assertThat(vacancy.externalId()).isEqualTo(data.externalId());
    assertThat(vacancy.title()).isEqualTo(data.title());
    assertThat(vacancy.employerName()).isEqualTo(data.employerName());
    assertThat(vacancy.area()).isEqualTo(data.area());
    assertThat(vacancy.salaryFrom()).isEqualTo(data.salaryFrom());
    assertThat(vacancy.salaryTo()).isEqualTo(data.salaryTo());
    assertThat(vacancy.currency()).isEqualTo(data.currency());
    assertThat(vacancy.url()).isEqualTo(data.url());
    assertThat(vacancy.publishedAt()).isEqualTo(data.publishedAt());
    assertThat(vacancy.firstSeenAt()).isEqualTo(NOW);
    assertThat(vacancy.lastSeenAt()).isEqualTo(NOW);
    assertThat(vacancy.status()).isEqualTo(VacancyStatus.NEW);
    assertThat(repository.findById(vacancy.id())).contains(vacancy);
    try (var connection =
            DriverManager.getConnection(
                "jdbc:sqlite:" + temporaryDirectory.resolve("job-monitor.db"));
        var statement = connection.prepareStatement("SELECT * FROM vacancies WHERE id = ?")) {
      statement.setString(1, vacancy.id().toString());
      try (var result = statement.executeQuery()) {
        assertThat(result.next()).isTrue();
        assertThat(result.getString("salary_from")).isEqualTo(data.salaryFrom().toPlainString());
        assertThat(result.getString("salary_to")).isEqualTo(data.salaryTo().toPlainString());
        assertThat(result.getString("first_seen_at")).isEqualTo("2026-10-13T10:00:00.000000000Z");
        assertThat(result.getString("published_at")).isEqualTo("2026-10-13T09:00:00.123456789Z");
      }
    }
  }

  @Test
  void repeatedUpsertPreservesIdentityAndAdvancesLastSeenWithoutDuplicates() {
    VacancyData data = data(HH, "1");
    Vacancy first = repository.upsert(data, NOW, List.of());
    Vacancy second = repository.upsert(data, NOW.plusSeconds(60), List.of());

    assertThat(second.id()).isEqualTo(first.id());
    assertThat(second.firstSeenAt()).isEqualTo(NOW);
    assertThat(second.lastSeenAt()).isEqualTo(NOW.plusSeconds(60));
    assertThat(second.status()).isEqualTo(VacancyStatus.NEW);
    assertThat(jdbcTemplate.queryForObject(VACANCY_COUNT, Integer.class)).isEqualTo(1);
  }

  @Test
  void updatesSourceFieldsWithoutResettingFirstSeenOrUserStatus() {
    Vacancy first = repository.upsert(data(HH, "1"), NOW, List.of());
    service.changeStatus(first.id(), VacancyStatus.SAVED);
    VacancyData updatedData =
        new VacancyData(
            HH,
            "1",
            "Kotlin backend",
            "Other employer",
            "Moscow",
            null,
            null,
            null,
            "https://hh.ru/vacancy/1?updated=true",
            null);

    Vacancy updated = repository.upsert(updatedData, NOW.plusSeconds(3600), List.of());

    assertThat(updated.id()).isEqualTo(first.id());
    assertThat(updated.title()).isEqualTo(updatedData.title());
    assertThat(updated.employerName()).isEqualTo(updatedData.employerName());
    assertThat(updated.area()).isEqualTo(updatedData.area());
    assertThat(updated.url()).isEqualTo(updatedData.url());
    assertThat(updated.salaryFrom()).isNull();
    assertThat(updated.salaryTo()).isNull();
    assertThat(updated.currency()).isNull();
    assertThat(updated.publishedAt()).isNull();
    assertThat(updated.firstSeenAt()).isEqualTo(NOW);
    assertThat(updated.lastSeenAt()).isEqualTo(NOW.plusSeconds(3600));
    assertThat(updated.status()).isEqualTo(VacancyStatus.SAVED);
    assertThat(jdbcTemplate.queryForObject(VACANCY_COUNT, Integer.class)).isEqualTo(1);
    Vacancy restored = repository.upsert(data(HH, "1"), NOW.plusSeconds(7200), List.of());
    assertThat(restored.salaryFrom()).isEqualTo(data(HH, "1").salaryFrom());
    assertThat(restored.salaryTo()).isEqualTo(data(HH, "1").salaryTo());
    assertThat(restored.publishedAt()).isEqualTo(data(HH, "1").publishedAt());
    assertThat(restored.status()).isEqualTo(VacancyStatus.SAVED);
  }

  @Test
  void deduplicatesBySourceAndExternalIdRatherThanTitleOrUrl() {
    Vacancy first = service.save(data(HH, "1"));
    Vacancy otherSource = service.save(data("FAKE", "1"));
    Vacancy otherId = service.save(data(HH, "2"));

    assertThat(otherSource.id()).isNotEqualTo(first.id());
    assertThat(otherId.id()).isNotEqualTo(first.id());
    assertThat(jdbcTemplate.queryForObject(VACANCY_COUNT, Integer.class)).isEqualTo(3);
  }

  @Test
  void matchesSeveralSearchesAndIgnoresRepeatedLinksAndUpserts() {
    SearchQuery firstSearch = search();
    SearchQuery secondSearch = search();
    Vacancy first = service.save(data(HH, "1"), firstSearch.id());
    Vacancy second = service.save(data(HH, "1"), secondSearch.id());
    service.linkToSearch(first.id(), firstSearch.id());
    service.linkToSearch(first.id(), secondSearch.id());

    assertThat(second.id()).isEqualTo(first.id());
    assertThat(jdbcTemplate.queryForObject(VACANCY_COUNT, Integer.class)).isEqualTo(1);
    assertThat(jdbcTemplate.queryForObject(MATCH_COUNT, Integer.class)).isEqualTo(2);
    assertThat(repository.findAll(null, null, firstSearch.id(), 0, 20, false).content())
        .extracting(Vacancy::id)
        .containsExactly(first.id());
    assertThat(repository.findAll(null, null, secondSearch.id(), 0, 20, false).totalElements())
        .isEqualTo(1);
    assertThat(repository.findAll(null, null, null, 0, 20, false).totalElements()).isEqualTo(1);
  }

  @Test
  void deletingSearchCascadesOnlyItsMatchesAndKeepsVacancyAndOtherMatches() {
    SearchQuery firstSearch = search();
    SearchQuery secondSearch = search();
    Vacancy vacancy = service.save(data(HH, "1"), firstSearch.id());
    service.linkToSearch(vacancy.id(), secondSearch.id());

    searchService.delete(firstSearch.id());

    assertThat(repository.findById(vacancy.id())).contains(vacancy);
    assertThat(jdbcTemplate.queryForObject(MATCH_COUNT, Integer.class)).isEqualTo(1);
    searchService.delete(secondSearch.id());
    assertThat(repository.findById(vacancy.id())).contains(vacancy);
    assertThat(jdbcTemplate.queryForObject(MATCH_COUNT, Integer.class)).isZero();
  }

  @Test
  void failedMatchRollsBackNewVacancyAndPreviouslyInsertedMatches() {
    SearchQuery search = search();

    assertThatThrownBy(
            () -> repository.upsert(data(HH, "1"), NOW, List.of(search.id(), UUID.randomUUID())))
        .isInstanceOf(DataAccessException.class)
        .hasMessageContaining("SQLITE_CONSTRAINT_FOREIGNKEY");

    assertThat(jdbcTemplate.queryForObject(VACANCY_COUNT, Integer.class)).isZero();
    assertThat(jdbcTemplate.queryForObject(MATCH_COUNT, Integer.class)).isZero();
  }

  @Test
  void failedMatchRollsBackExistingVacancyUpdate() {
    Vacancy original = repository.upsert(data(HH, "1"), NOW, List.of());
    service.changeStatus(original.id(), VacancyStatus.APPLIED);
    Vacancy before = service.findById(original.id());
    VacancyData changed =
        new VacancyData(
            HH,
            "1",
            "Changed",
            "Employer",
            "Area",
            null,
            null,
            null,
            "https://example.com/changed",
            null);

    assertThatThrownBy(
            () -> repository.upsert(changed, NOW.plusSeconds(60), List.of(UUID.randomUUID())))
        .isInstanceOf(DataAccessException.class);

    assertThat(repository.findById(original.id())).contains(before);
  }

  @Test
  void savesBatchAtomicallyWithSearchMatchesAndAllowsRepeatedItems() {
    SearchQuery search = search();
    List<Vacancy> saved =
        service.saveAll(List.of(data(HH, "1"), data(HH, "2"), data(HH, "1")), search.id());

    assertThat(saved).hasSize(3);
    assertThat(saved.getFirst()).isEqualTo(saved.getLast());
    assertThat(saved).extracting(Vacancy::firstSeenAt).containsOnly(saved.getFirst().firstSeenAt());
    assertThat(jdbcTemplate.queryForObject(VACANCY_COUNT, Integer.class)).isEqualTo(2);
    assertThat(jdbcTemplate.queryForObject(MATCH_COUNT, Integer.class)).isEqualTo(2);
    assertThat(service.saveAll(List.of())).isEmpty();
  }

  @Test
  void batchDatabaseFailureRollsBackEarlierUpsertAndLink() {
    SearchQuery search = search();
    Vacancy original = repository.upsert(data(HH, "1"), NOW, List.of());
    jdbcTemplate.execute(
        """
        CREATE TRIGGER reject_fixture BEFORE INSERT ON vacancies
        WHEN NEW.external_id = 'fail'
        BEGIN SELECT RAISE(ABORT, 'fixture rejection'); END
        """);
    try {
      assertThatThrownBy(
              () -> service.saveAll(List.of(data(HH, "1"), data(HH, "fail")), search.id()))
          .isInstanceOf(DataAccessException.class)
          .hasMessageContaining("fixture rejection");
      assertThat(repository.findById(original.id())).contains(original);
      assertThat(jdbcTemplate.queryForObject(VACANCY_COUNT, Integer.class)).isEqualTo(1);
      assertThat(jdbcTemplate.queryForObject(MATCH_COUNT, Integer.class)).isZero();
    } finally {
      jdbcTemplate.execute("DROP TRIGGER reject_fixture");
    }
  }

  @Test
  void missingSearchOrVacancyCannotLeaveOrphanMatches() {
    UUID missing = UUID.randomUUID();
    assertThatThrownBy(() -> service.save(data(HH, "1"), missing))
        .isInstanceOf(SearchNotFoundException.class);
    assertThat(jdbcTemplate.queryForObject(VACANCY_COUNT, Integer.class)).isZero();
    Vacancy vacancy = service.save(data(HH, "1"));
    assertThatThrownBy(() -> service.linkToSearch(vacancy.id(), missing))
        .isInstanceOf(SearchNotFoundException.class);
    assertThatThrownBy(() -> service.linkToSearch(missing, search().id()))
        .isInstanceOf(VacancyNotFoundException.class);
    assertThatThrownBy(() -> repository.linkToSearch(missing, search().id()))
        .isInstanceOf(DataAccessException.class)
        .hasMessageContaining("SQLITE_CONSTRAINT_FOREIGNKEY");
    assertThat(jdbcTemplate.queryForObject(MATCH_COUNT, Integer.class)).isZero();
  }

  @Test
  void databaseEnforcesIdentityMatchUniquenessAndStatusConstraints() {
    SearchQuery search = search();
    Vacancy vacancy = service.save(data(HH, "1"), search.id());
    assertThatThrownBy(
            () ->
                jdbcTemplate.update(
                    """
        INSERT INTO vacancies SELECT ?, source, external_id, title, employer_name, area,
          salary_from, salary_to, currency, url, published_at, first_seen_at, last_seen_at, status
        FROM vacancies WHERE id = ?
        """,
                    UUID.randomUUID().toString(),
                    vacancy.id().toString()))
        .isInstanceOf(DataAccessException.class)
        .hasMessageContaining("SQLITE_CONSTRAINT_UNIQUE");
    assertThatThrownBy(
            () ->
                jdbcTemplate.update(
                    "INSERT INTO vacancy_search_matches (vacancy_id, search_query_id) VALUES (?, ?)",
                    vacancy.id().toString(),
                    search.id().toString()))
        .isInstanceOf(DataAccessException.class)
        .hasMessageContaining("SQLITE_CONSTRAINT_UNIQUE");
    assertThatThrownBy(() -> jdbcTemplate.update("UPDATE vacancies SET status = 'UNKNOWN'"))
        .isInstanceOf(DataAccessException.class)
        .hasMessageContaining("SQLITE_CONSTRAINT_CHECK");
    assertThat(jdbcTemplate.queryForObject(VACANCY_COUNT, Integer.class)).isEqualTo(1);
    assertThat(jdbcTemplate.queryForObject(MATCH_COUNT, Integer.class)).isEqualTo(1);
  }

  @Test
  void filtersPagesAndSortsFractionalSecondsChronologicallyWithStableTies() {
    SearchQuery search = search();
    Vacancy earliest = repository.upsert(data(HH, "1"), NOW, List.of(search.id()));
    Vacancy later = repository.upsert(data("FAKE", "2"), NOW.plusNanos(1), List.of(search.id()));
    Vacancy latest = repository.upsert(data(HH, "3"), NOW.plusSeconds(1), List.of());
    service.changeStatus(earliest.id(), VacancyStatus.SAVED);

    assertThat(repository.findAll(null, null, null, 0, 2, true).content())
        .extracting(Vacancy::id)
        .containsExactly(earliest.id(), later.id());
    VacancyPage secondPage = repository.findAll(null, null, null, 1, 2, true);
    assertThat(secondPage.content()).containsExactly(latest);
    assertThat(secondPage.totalElements()).isEqualTo(3);
    assertThat(repository.findAll(null, null, null, 0, 20, false).content())
        .extracting(Vacancy::id)
        .containsExactly(latest.id(), later.id(), earliest.id());
    assertThat(repository.findAll(VacancyStatus.SAVED, HH, search.id(), 0, 20, false).content())
        .extracting(Vacancy::id)
        .containsExactly(earliest.id());
    assertThat(repository.findAll(null, HH, null, 0, 20, false).totalElements()).isEqualTo(2);
    assertThat(repository.findAll(null, "HH' OR 1=1 --", null, 0, 20, false).totalElements())
        .isZero();
    assertThat(repository.findAll(null, null, UUID.randomUUID(), 0, 20, false).content()).isEmpty();
    assertThat(repository.findAll(null, null, null, Integer.MAX_VALUE, 100, false).content())
        .isEmpty();
    Vacancy tie = repository.upsert(data(HH, "4"), NOW.plusSeconds(1), List.of());
    List<String> sortedIds =
        List.of(latest.id().toString(), tie.id().toString()).stream().sorted().toList();
    assertThat(repository.findAll(null, null, null, 2, 1, true).content())
        .extracting(item -> item.id().toString())
        .containsExactly(sortedIds.getFirst());
    assertThat(repository.findAll(null, null, null, 3, 1, true).content())
        .extracting(item -> item.id().toString())
        .containsExactly(sortedIds.getLast());
  }

  @Test
  void upgradesV2ToV3WithoutLosingMetadataOrSearchFields() {
    var dataSource =
        new DriverManagerDataSource("jdbc:sqlite:" + temporaryDirectory.resolve("upgrade.db"));
    Flyway.configure().dataSource(dataSource).target("2").load().migrate();
    var database = new JdbcTemplate(dataSource);
    String id = UUID.randomUUID().toString();
    String expression = "  (Java AND \"Spring Boot\") OR NOT senior  ";
    database.update(
        "INSERT INTO application_metadata (key, value) VALUES (?, ?)", "existing", "preserved");
    database.update(
        """
        INSERT INTO search_queries
          (id, name, query_text, interval_minutes, enabled, created_at, updated_at, next_run_at)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?)
        """,
        id,
        "Existing",
        expression,
        30,
        true,
        NOW.toString(),
        NOW.toString(),
        NOW.plusSeconds(1800).toString());
    var before = database.queryForMap("SELECT * FROM search_queries WHERE id = ?", id);

    Flyway flyway = Flyway.configure().dataSource(dataSource).target("3").load();

    assertThat(flyway.migrate().migrationsExecuted).isEqualTo(1);
    assertThat(flyway.info().current().getVersion().getVersion()).isEqualTo("3");
    assertThat(database.queryForMap("SELECT * FROM search_queries WHERE id = ?", id))
        .isEqualTo(before);
    assertThat(
            database.queryForObject(
                "SELECT value FROM application_metadata WHERE key = 'existing'", String.class))
        .isEqualTo("preserved");
    assertThat(database.queryForObject(VACANCY_COUNT, Integer.class)).isZero();
    assertThat(database.queryForObject(MATCH_COUNT, Integer.class)).isZero();
    assertThat(flyway.migrate().migrationsExecuted).isZero();
  }

  private SearchQuery search() {
    return searchService.create(new SearchQueryRequest("Java", "Java AND NOT senior", 30, true));
  }

  static VacancyData data(String source, String externalId) {
    return new VacancyData(
        source,
        externalId,
        "Java backend",
        "Employer",
        "Saint Petersburg",
        new BigDecimal("12345678901234567890.123456789"),
        new BigDecimal("22345678901234567890.987654321"),
        "RUB",
        "https://hh.ru/vacancy/1",
        NOW.minusSeconds(3600).plusNanos(123456789));
  }
}
