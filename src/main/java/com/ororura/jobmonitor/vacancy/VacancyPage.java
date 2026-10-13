package com.ororura.jobmonitor.vacancy;

import java.util.List;

public record VacancyPage(List<Vacancy> content, int page, int size, long totalElements) {
  public VacancyPage {
    content = List.copyOf(content);
  }
}
