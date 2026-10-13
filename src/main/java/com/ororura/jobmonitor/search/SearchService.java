package com.ororura.jobmonitor.search;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SearchService {

  private final SearchRepository repository;
  private final Clock clock;

  SearchService(SearchRepository repository, Clock clock) {
    this.repository = repository;
    this.clock = clock;
  }

  public List<SearchQuery> findAll() {
    return repository.findAll();
  }

  public SearchQuery findById(UUID id) {
    return repository.findById(id).orElseThrow(() -> new SearchNotFoundException(id));
  }

  public SearchQuery create(SearchQueryRequest request) {
    validate(request);
    Instant now = clock.instant();
    SearchQuery query =
        new SearchQuery(
            UUID.randomUUID(),
            request.name(),
            request.queryText(),
            request.intervalMinutes(),
            request.enabled(),
            now,
            now,
            null);
    repository.insert(query);
    return query;
  }

  @Transactional
  public SearchQuery update(UUID id, SearchQueryRequest request) {
    validate(request);
    SearchQuery existing = findById(id);
    SearchQuery updated =
        new SearchQuery(
            id,
            request.name(),
            request.queryText(),
            request.intervalMinutes(),
            request.enabled(),
            existing.createdAt(),
            clock.instant(),
            existing.nextRunAt());
    if (!repository.update(updated)) {
      throw new SearchNotFoundException(id);
    }
    return updated;
  }

  public void delete(UUID id) {
    if (!repository.deleteById(id)) {
      throw new SearchNotFoundException(id);
    }
  }

  private static void validate(SearchQueryRequest request) {
    if (request == null) {
      throw new SearchValidationException("Request body is required");
    }
    if (request.name() == null || request.name().isBlank()) {
      throw new SearchValidationException("Name must not be blank");
    }
    if (request.queryText() == null || request.queryText().isBlank()) {
      throw new SearchValidationException("Query text must not be blank");
    }
    if (request.intervalMinutes() == null || request.intervalMinutes() < 10) {
      throw new SearchValidationException("Interval must be at least 10 minutes");
    }
    if (request.enabled() == null) {
      throw new SearchValidationException("Enabled flag is required");
    }
  }
}
