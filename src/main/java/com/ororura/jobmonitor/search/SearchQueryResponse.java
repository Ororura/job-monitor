package com.ororura.jobmonitor.search;

import java.time.Instant;
import java.util.UUID;

public record SearchQueryResponse(
    UUID id,
    String name,
    String queryText,
    int intervalMinutes,
    boolean enabled,
    Instant createdAt,
    Instant updatedAt,
    Instant nextRunAt) {

  static SearchQueryResponse from(SearchQuery query) {
    return new SearchQueryResponse(
        query.id(),
        query.name(),
        query.queryText(),
        query.intervalMinutes(),
        query.enabled(),
        query.createdAt(),
        query.updatedAt(),
        query.nextRunAt());
  }
}
