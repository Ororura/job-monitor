package com.ororura.jobmonitor.vacancy;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class VacancyRepository {

  // A fixed fractional width keeps TEXT ordering chronological, including within one second.
  private static final DateTimeFormatter TIMESTAMP_FORMAT =
      new DateTimeFormatterBuilder().appendInstant(9).toFormatter();

  private static final RowMapper<Vacancy> ROW_MAPPER =
      (result, rowNumber) ->
          new Vacancy(
              UUID.fromString(result.getString("id")),
              result.getString("source"),
              result.getString("external_id"),
              result.getString("title"),
              result.getString("employer_name"),
              result.getString("area"),
              decimal(result.getString("salary_from")),
              decimal(result.getString("salary_to")),
              result.getString("currency"),
              result.getString("url"),
              instant(result.getString("published_at")),
              Instant.parse(result.getString("first_seen_at")),
              Instant.parse(result.getString("last_seen_at")),
              VacancyStatus.valueOf(result.getString("status")));

  private final JdbcTemplate jdbcTemplate;

  VacancyRepository(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
  }

  @Transactional
  public Vacancy upsert(VacancyData data, Instant seenAt, Collection<UUID> searchQueryIds) {
    jdbcTemplate.update(
        """
        INSERT INTO vacancies
          (id, source, external_id, title, employer_name, area, salary_from, salary_to,
           currency, url, published_at, first_seen_at, last_seen_at, status)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'NEW')
        ON CONFLICT(source, external_id) DO UPDATE SET
          title = excluded.title, employer_name = excluded.employer_name, area = excluded.area,
          salary_from = excluded.salary_from, salary_to = excluded.salary_to,
          currency = excluded.currency, url = excluded.url, published_at = excluded.published_at,
          last_seen_at = excluded.last_seen_at
        """,
        UUID.randomUUID().toString(),
        data.source(),
        data.externalId(),
        data.title(),
        data.employerName(),
        data.area(),
        decimalText(data.salaryFrom()),
        decimalText(data.salaryTo()),
        data.currency(),
        data.url(),
        timestamp(data.publishedAt()),
        timestamp(seenAt),
        timestamp(seenAt));
    Vacancy vacancy =
        Objects.requireNonNull(
            jdbcTemplate.queryForObject(
                "SELECT * FROM vacancies WHERE source = ? AND external_id = ?",
                ROW_MAPPER,
                data.source(),
                data.externalId()));
    for (UUID searchQueryId : searchQueryIds) {
      linkToSearch(vacancy.id(), searchQueryId);
    }
    return vacancy;
  }

  public void linkToSearch(UUID vacancyId, UUID searchQueryId) {
    jdbcTemplate.update(
        """
        INSERT INTO vacancy_search_matches (vacancy_id, search_query_id) VALUES (?, ?)
        ON CONFLICT(vacancy_id, search_query_id) DO NOTHING
        """,
        vacancyId.toString(),
        searchQueryId.toString());
  }

  public Optional<Vacancy> findById(UUID id) {
    return jdbcTemplate
        .query("SELECT * FROM vacancies WHERE id = ?", ROW_MAPPER, id.toString())
        .stream()
        .findFirst();
  }

  public boolean exists(String source, String externalId) {
    return Boolean.TRUE.equals(
        jdbcTemplate.queryForObject(
            "SELECT EXISTS(SELECT 1 FROM vacancies WHERE source = ? AND external_id = ?)",
            Boolean.class,
            source,
            externalId));
  }

  public boolean updateStatus(UUID id, VacancyStatus status) {
    return jdbcTemplate.update(
            "UPDATE vacancies SET status = ? WHERE id = ?", status.name(), id.toString())
        == 1;
  }

  @Transactional(readOnly = true)
  public VacancyPage findAll(
      VacancyStatus status,
      String source,
      UUID searchQueryId,
      int page,
      int size,
      boolean ascending) {
    StringBuilder filter = new StringBuilder(" WHERE 1 = 1");
    List<Object> parameters = new ArrayList<>();
    if (status != null) {
      filter.append(" AND v.status = ?");
      parameters.add(status.name());
    }
    if (source != null) {
      filter.append(" AND v.source = ?");
      parameters.add(source);
    }
    if (searchQueryId != null) {
      filter.append(
          """
           AND EXISTS (SELECT 1 FROM vacancy_search_matches m
             WHERE m.vacancy_id = v.id AND m.search_query_id = ?)
          """);
      parameters.add(searchQueryId.toString());
    }
    long total =
        Objects.requireNonNull(
            jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM vacancies v" + filter, Long.class, parameters.toArray()));
    String direction = ascending ? " ASC" : " DESC";
    parameters.add(size);
    parameters.add((long) page * size);
    List<Vacancy> content =
        jdbcTemplate.query(
            "SELECT v.* FROM vacancies v"
                + filter
                + " ORDER BY v.first_seen_at"
                + direction
                + ", v.id"
                + direction
                + " LIMIT ? OFFSET ?",
            ROW_MAPPER,
            parameters.toArray());
    return new VacancyPage(content, page, size, total);
  }

  private static String timestamp(Instant value) {
    return value == null ? null : TIMESTAMP_FORMAT.format(value);
  }

  private static Instant instant(String value) {
    return value == null ? null : Instant.parse(value);
  }

  private static String decimalText(BigDecimal value) {
    return value == null ? null : value.toPlainString();
  }

  private static BigDecimal decimal(String value) {
    return value == null ? null : new BigDecimal(value);
  }
}
