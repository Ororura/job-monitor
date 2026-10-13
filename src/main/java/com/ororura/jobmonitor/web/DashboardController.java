package com.ororura.jobmonitor.web;

import com.ororura.jobmonitor.collection.CollectionProperties;
import com.ororura.jobmonitor.collection.CollectionRunRepository;
import com.ororura.jobmonitor.collection.CollectionRunStatus;
import com.ororura.jobmonitor.collection.CollectionService;
import com.ororura.jobmonitor.search.SearchRepository;
import com.ororura.jobmonitor.vacancy.VacancyRepository;
import com.ororura.jobmonitor.vacancy.VacancyService;
import java.time.Clock;
import java.time.Duration;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class DashboardController {
  private final VacancyService vacancies;
  private final VacancyRepository vacancyRepository;
  private final SearchRepository searches;
  private final CollectionService collections;
  private final CollectionRunRepository runs;
  private final CollectionProperties properties;
  private final Clock clock;

  DashboardController(
      VacancyService vacancies,
      VacancyRepository vacancyRepository,
      SearchRepository searches,
      CollectionService collections,
      CollectionRunRepository runs,
      CollectionProperties properties,
      Clock clock) {
    this.vacancies = vacancies;
    this.vacancyRepository = vacancyRepository;
    this.searches = searches;
    this.collections = collections;
    this.runs = runs;
    this.properties = properties;
    this.clock = clock;
  }

  @GetMapping("/")
  String dashboard(Model model) {
    var latest = vacancies.findAll(null, null, null, 0, 5, "firstSeenAt,desc");
    var now = clock.instant();
    var since = now.minus(Duration.ofHours(24));
    model.addAttribute("totalVacancies", latest.totalElements());
    model.addAttribute("newVacancies", vacancyRepository.countFirstSeenBetween(since, now));
    model.addAttribute("activeSearches", searches.countEnabled());
    model.addAttribute("successRuns", runs.countBetween(CollectionRunStatus.SUCCESS, since, now));
    model.addAttribute(
        "failedRuns",
        runs.countBetween(CollectionRunStatus.FAILED, since, now)
            + runs.countBetween(CollectionRunStatus.INTERRUPTED, since, now));
    model.addAttribute("vacancies", latest.content());
    model.addAttribute("runs", collections.findAll(null, null, 0, 5).content());
    model.addAttribute("failures", runs.findRecentFailures(3));
    model.addAttribute("upcoming", searches.findUpcoming(5));
    model.addAttribute("automatic", properties.enabled());
    return "dashboard";
  }
}
