package com.ororura.jobmonitor.collection;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class CollectionRunRepository {

  private static final DateTimeFormatter TIMESTAMP_FORMAT =
      new DateTimeFormatterBuilder().appendInstant(9).toFormatter();
  private static final RowMapper<CollectionRun> ROW_MAPPER =
      (result, rowNumber) -> {
        String searchId = result.getString("search_query_id");
        String finishedAt = result.getString("finished_at");
        return new CollectionRun(
            UUID.fromString(result.getString("id")),
            searchId == null ? null : UUID.fromString(searchId),
            result.getString("search_query_name"),
            result.getString("source"),
            CollectionRunStatus.valueOf(result.getString("status")),
            Instant.parse(result.getString("started_at")),
            finishedAt == null ? null : Instant.parse(finishedAt),
            result.getInt("fetched_count"),
            result.getInt("created_count"),
            result.getInt("updated_count"),
            result.getString("error_message"));
      };

  private final JdbcTemplate jdbcTemplate;

  CollectionRunRepository(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
  }

  public void insert(CollectionRun run) {
    jdbcTemplate.update(
        """
        INSERT INTO collection_runs
          (id, search_query_id, search_query_name, source, status, started_at)
        VALUES (?, ?, ?, ?, 'RUNNING', ?)
        """,
        run.id().toString(),
        run.searchQueryId().toString(),
        run.searchQueryName(),
        run.source(),
        TIMESTAMP_FORMAT.format(run.startedAt()));
  }

  public void finish(
      UUID id,
      CollectionRunStatus status,
      Instant finishedAt,
      int fetched,
      int created,
      int updated,
      String error) {
    jdbcTemplate.update(
        """
        UPDATE collection_runs SET status = ?, finished_at = ?, fetched_count = ?,
          created_count = ?, updated_count = ?, error_message = ?
        WHERE id = ? AND status = 'RUNNING'
        """,
        status.name(),
        TIMESTAMP_FORMAT.format(finishedAt),
        fetched,
        created,
        updated,
        error,
        id.toString());
  }

  public int interruptRunning(Instant now) {
    return jdbcTemplate.update(
        """
        UPDATE collection_runs SET status = 'INTERRUPTED', finished_at = ?,
          error_message = 'Application stopped before collection completed'
        WHERE status = 'RUNNING'
        """,
        TIMESTAMP_FORMAT.format(now));
  }

  public List<CollectionRun> findRunning() {
    return jdbcTemplate.query("SELECT * FROM collection_runs WHERE status = 'RUNNING'", ROW_MAPPER);
  }

  public Optional<CollectionRun> findById(UUID id) {
    return jdbcTemplate
        .query("SELECT * FROM collection_runs WHERE id = ?", ROW_MAPPER, id.toString())
        .stream()
        .findFirst();
  }

  @Transactional(readOnly = true)
  public CollectionRunPage findAll(UUID searchId, CollectionRunStatus status, int page, int size) {
    StringBuilder filter = new StringBuilder(" WHERE 1 = 1");
    List<Object> parameters = new ArrayList<>();
    if (searchId != null) {
      filter.append(" AND search_query_id = ?");
      parameters.add(searchId.toString());
    }
    if (status != null) {
      filter.append(" AND status = ?");
      parameters.add(status.name());
    }
    long total =
        Objects.requireNonNull(
            jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM collection_runs" + filter, Long.class, parameters.toArray()));
    parameters.add(size);
    parameters.add((long) page * size);
    List<CollectionRun> content =
        jdbcTemplate.query(
            "SELECT * FROM collection_runs"
                + filter
                + " ORDER BY started_at DESC, id DESC LIMIT ? OFFSET ?",
            ROW_MAPPER,
            parameters.toArray());
    return new CollectionRunPage(content, page, size, total);
  }
}
