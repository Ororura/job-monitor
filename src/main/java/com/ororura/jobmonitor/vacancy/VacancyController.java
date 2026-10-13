package com.ororura.jobmonitor.vacancy;

import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/vacancies")
public class VacancyController {

  private final VacancyService service;

  VacancyController(VacancyService service) {
    this.service = service;
  }

  @GetMapping
  public VacancyPage list(
      @RequestParam(required = false) VacancyStatus status,
      @RequestParam(required = false) String source,
      @RequestParam(required = false) UUID searchQueryId,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size,
      @RequestParam(defaultValue = "firstSeenAt,desc") String sort) {
    return service.findAll(status, source, searchQueryId, page, size, sort);
  }

  @GetMapping("/{id}")
  public Vacancy get(@PathVariable UUID id) {
    return service.findById(id);
  }

  @PatchMapping("/{id}/status")
  public Vacancy changeStatus(@PathVariable UUID id, @RequestBody VacancyStatusRequest request) {
    return service.changeStatus(id, request == null ? null : request.status());
  }
}
