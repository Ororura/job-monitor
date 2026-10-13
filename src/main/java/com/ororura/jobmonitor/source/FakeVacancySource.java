package com.ororura.jobmonitor.source;

import com.ororura.jobmonitor.search.SearchQuery;
import com.ororura.jobmonitor.vacancy.VacancyData;
import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class FakeVacancySource implements VacancySource {

  @Override
  public String source() {
    return "FAKE";
  }

  @Override
  public List<VacancyData> fetch(SearchQuery query) {
    VacancyData java = vacancy("1", "Java backend developer");
    VacancyData kotlin = vacancy("2", "Kotlin backend developer");
    // Repeated identities exercise ingestion both within a batch and across searches/runs.
    return List.of(java, kotlin, java);
  }

  private VacancyData vacancy(String id, String title) {
    return new VacancyData(
        source(),
        id,
        title,
        "Example employer",
        "Remote",
        null,
        null,
        null,
        "https://example.invalid/vacancies/" + id,
        Instant.parse("2026-10-13T09:00:00Z"));
  }
}
