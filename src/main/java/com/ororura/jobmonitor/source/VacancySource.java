package com.ororura.jobmonitor.source;

import com.ororura.jobmonitor.search.SearchQuery;
import com.ororura.jobmonitor.vacancy.VacancyData;
import java.util.List;

public interface VacancySource {
  String source();

  List<VacancyData> fetch(SearchQuery query);
}
