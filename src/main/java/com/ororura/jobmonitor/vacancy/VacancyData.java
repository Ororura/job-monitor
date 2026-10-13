package com.ororura.jobmonitor.vacancy;

import java.math.BigDecimal;
import java.time.Instant;

public record VacancyData(
    String source,
    String externalId,
    String title,
    String employerName,
    String area,
    BigDecimal salaryFrom,
    BigDecimal salaryTo,
    String currency,
    String url,
    Instant publishedAt) {}
