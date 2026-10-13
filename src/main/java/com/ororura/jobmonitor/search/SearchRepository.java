package com.ororura.jobmonitor.search;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class SearchRepository {

  private static final RowMapper<SearchQuery> ROW_MAPPER =
      (result, rowNumber) -> {
        String nextRunAt = result.getString("next_run_at");
        return new SearchQuery(
            UUID.fromString(result.getString("id")),
            result.getString("name"),
            result.getString("query_text"),
            result.getInt("interval_minutes"),
            result.getBoolean("enabled"),
            Instant.parse(result.getString("created_at")),
            Instant.parse(result.getString("updated_at")),
            nextRunAt == null ? null : Instant.parse(nextRunAt));
      };

  private final JdbcTemplate jdbcTemplate;

  SearchRepository(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
  }

  public List<SearchQuery> findAll() {
    return jdbcTemplate.query("SELECT * FROM search_queries ORDER BY created_at, id", ROW_MAPPER);
  }

  public Optional<SearchQuery> findById(UUID id) {
    return jdbcTemplate
        .query("SELECT * FROM search_queries WHERE id = ?", ROW_MAPPER, id.toString())
        .stream()
        .findFirst();
  }

  public void insert(SearchQuery query) {
    jdbcTemplate.update(
        """
        INSERT INTO search_queries
          (id, name, query_text, interval_minutes, enabled, created_at, updated_at, next_run_at)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?)
        """,
        query.id().toString(),
        query.name(),
        query.queryText(),
        query.intervalMinutes(),
        query.enabled(),
        query.createdAt().toString(),
        query.updatedAt().toString(),
        query.nextRunAt() == null ? null : query.nextRunAt().toString());
  }

  public boolean update(SearchQuery query) {
    return jdbcTemplate.update(
            """
        UPDATE search_queries
        SET name = ?, query_text = ?, interval_minutes = ?, enabled = ?, updated_at = ?, next_run_at = ?
        WHERE id = ?
        """,
            query.name(),
            query.queryText(),
            query.intervalMinutes(),
            query.enabled(),
            query.updatedAt().toString(),
            query.nextRunAt() == null ? null : query.nextRunAt().toString(),
            query.id().toString())
        == 1;
  }

  public boolean deleteById(UUID id) {
    return jdbcTemplate.update("DELETE FROM search_queries WHERE id = ?", id.toString()) == 1;
  }
}
