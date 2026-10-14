package com.ororura.jobmonitor.web;

import com.ororura.jobmonitor.search.SearchService;
import com.ororura.jobmonitor.vacancy.VacancyService;
import com.ororura.jobmonitor.vacancy.VacancyStatus;
import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
public class VacancyPageController {
  private final VacancyService vacancies;
  private final SearchService searches;

  VacancyPageController(VacancyService vacancies, SearchService searches) {
    this.vacancies = vacancies;
    this.searches = searches;
  }

  @GetMapping("/vacancies")
  String list(
      @RequestParam(required = false) VacancyStatus status,
      @RequestParam(required = false) String source,
      @RequestParam(required = false) UUID searchQueryId,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size,
      @RequestParam(defaultValue = "firstSeenAt,desc") String sort,
      Model model,
      HttpServletRequest request) {
    String normalizedSource = source == null || source.isBlank() ? null : source;
    model.addAttribute(
        "result", vacancies.findAll(status, normalizedSource, searchQueryId, page, size, sort));
    model.addAttribute("searches", searches.findAll());
    model.addAttribute("status", status);
    model.addAttribute("source", normalizedSource);
    model.addAttribute("searchQueryId", searchQueryId);
    model.addAttribute("sort", sort);
    return WebAdvice.htmx(request) ? "vacancies/list :: results" : "vacancies/list";
  }

  @PostMapping("/vacancies/{id}/status")
  String status(
      @PathVariable UUID id,
      @RequestParam VacancyStatus newStatus,
      @RequestParam(required = false) VacancyStatus status,
      @RequestParam(required = false) String source,
      @RequestParam(required = false) UUID searchQueryId,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size,
      @RequestParam(defaultValue = "firstSeenAt,desc") String sort,
      Model model,
      HttpServletRequest request,
      RedirectAttributes flash) {
    // Validate the retained filter before applying the mutation.
    vacancies.findAll(
        status,
        source == null || source.isBlank() ? null : source,
        searchQueryId,
        page,
        size,
        sort);
    vacancies.changeStatus(id, newStatus);
    if (!WebAdvice.htmx(request)) {
      flash.addFlashAttribute("message", "Статус вакансии обновлён.");
      flash.addAttribute("status", status);
      flash.addAttribute("source", source);
      flash.addAttribute("searchQueryId", searchQueryId);
      flash.addAttribute("page", page);
      flash.addAttribute("size", size);
      flash.addAttribute("sort", sort);
      return "redirect:/vacancies";
    }
    model.addAttribute("message", "Статус вакансии обновлён.");
    return list(status, source, searchQueryId, page, size, sort, model, request);
  }
}
