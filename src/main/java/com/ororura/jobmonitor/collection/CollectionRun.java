package com.ororura.jobmonitor.collection;

import java.time.Instant;
import java.util.UUID;

public record CollectionRun(
    UUID id,
    UUID searchQueryId,
    String searchQueryName,
    String source,
    CollectionRunStatus status,
    Instant startedAt,
    Instant finishedAt,
    int fetchedCount,
    int createdCount,
    int updatedCount,
    String errorMessage) {}
