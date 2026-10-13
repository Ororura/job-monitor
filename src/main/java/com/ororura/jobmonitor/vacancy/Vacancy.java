package com.ororura.jobmonitor.vacancy;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record Vacancy(
    UUID id,
    String source,
    String externalId,
    String title,
    String employerName,
    String area,
    BigDecimal salaryFrom,
    BigDecimal salaryTo,
    String currency,
    String url,
    Instant publishedAt,
    Instant firstSeenAt,
    Instant lastSeenAt,
    VacancyStatus status) {}
