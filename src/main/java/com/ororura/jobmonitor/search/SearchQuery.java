package com.ororura.jobmonitor.search;

import java.time.Instant;
import java.util.UUID;

public record SearchQuery(
    UUID id,
    String name,
    String queryText,
    int intervalMinutes,
    boolean enabled,
    Instant createdAt,
    Instant updatedAt,
    Instant nextRunAt) {}
