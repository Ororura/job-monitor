package com.ororura.jobmonitor.source;

import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class VacancySources {
  private final List<VacancySource> adapters;

  public VacancySources(List<VacancySource> adapters) {
    this.adapters = List.copyOf(adapters);
  }

  public VacancySource resolve(String source) {
    return adapters.stream()
        .filter(adapter -> source.equals(adapter.source()))
        .findFirst()
        .orElseThrow(() -> new BrowserSourceException(BrowserSourceException.Reason.CONFIGURATION));
  }
}
