package com.ororura.jobmonitor.vacancy;

import com.ororura.jobmonitor.search.SearchNotFoundException;
import com.ororura.jobmonitor.search.SearchRepository;
import java.math.BigDecimal;
import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class VacancyService {

  private final VacancyRepository repository;
  private final SearchRepository searchRepository;
  private final Clock clock;

  VacancyService(VacancyRepository repository, SearchRepository searchRepository, Clock clock) {
    this.repository = repository;
    this.searchRepository = searchRepository;
    this.clock = clock;
  }

  @Transactional
  public Vacancy save(VacancyData data) {
    return save(data, null);
  }

  @Transactional
  public Vacancy save(VacancyData data, UUID searchQueryId) {
    VacancyData normalized = normalize(data);
    List<UUID> searchIds = searchIds(searchQueryId);
    return repository.upsert(normalized, clock.instant(), searchIds);
  }

  @Transactional
  public List<Vacancy> saveAll(List<VacancyData> data) {
    return saveAll(data, null);
  }

  @Transactional
  public List<Vacancy> saveAll(List<VacancyData> data, UUID searchQueryId) {
    if (data == null) {
      throw new VacancyValidationException("Vacancy list is required");
    }
    List<VacancyData> normalized = data.stream().map(VacancyService::normalize).toList();
    List<UUID> searchIds = searchIds(searchQueryId);
    Instant seenAt = clock.instant();
    return normalized.stream().map(item -> repository.upsert(item, seenAt, searchIds)).toList();
  }

  @Transactional
  public Vacancy linkToSearch(UUID vacancyId, UUID searchQueryId) {
    requireId(searchQueryId);
    Vacancy vacancy = findById(vacancyId);
    searchIds(searchQueryId);
    repository.linkToSearch(vacancyId, searchQueryId);
    return vacancy;
  }

  @Transactional
  public Vacancy changeStatus(UUID id, VacancyStatus status) {
    requireId(id);
    if (status == null) {
      throw new VacancyValidationException("Status is required");
    }
    if (!repository.updateStatus(id, status)) {
      throw new VacancyNotFoundException(id);
    }
    return findById(id);
  }

  public Vacancy findById(UUID id) {
    requireId(id);
    return repository.findById(id).orElseThrow(() -> new VacancyNotFoundException(id));
  }

  public VacancyPage findAll(
      VacancyStatus status, String source, UUID searchQueryId, int page, int size, String sort) {
    if (page < 0 || size < 1 || size > 100) {
      throw new VacancyValidationException("Page must be non-negative and size must be 1 to 100");
    }
    if (!"firstSeenAt,asc".equals(sort) && !"firstSeenAt,desc".equals(sort)) {
      throw new VacancyValidationException("Sort must be firstSeenAt,asc or firstSeenAt,desc");
    }
    String normalizedSource = source == null ? null : required(source, "Source");
    return repository.findAll(
        status, normalizedSource, searchQueryId, page, size, "firstSeenAt,asc".equals(sort));
  }

  private List<UUID> searchIds(UUID searchQueryId) {
    if (searchQueryId == null) {
      return List.of();
    }
    if (searchRepository.findById(searchQueryId).isEmpty()) {
      throw new SearchNotFoundException(searchQueryId);
    }
    return List.of(searchQueryId);
  }

  private static VacancyData normalize(VacancyData data) {
    if (data == null) {
      throw new VacancyValidationException("Vacancy data is required");
    }
    String source = required(data.source(), "Source");
    String externalId = required(data.externalId(), "External ID");
    String title = required(data.title(), "Title");
    String employerName = required(data.employerName(), "Employer name");
    String area = required(data.area(), "Area");
    String url = required(data.url(), "URL");
    URI uri;
    try {
      uri = URI.create(url);
    } catch (IllegalArgumentException exception) {
      throw new VacancyValidationException("URL must be an absolute HTTP or HTTPS URL");
    }
    if ((!"http".equalsIgnoreCase(uri.getScheme()) && !"https".equalsIgnoreCase(uri.getScheme()))
        || uri.getHost() == null
        || uri.getUserInfo() != null) {
      throw new VacancyValidationException("URL must be an absolute HTTP or HTTPS URL");
    }
    validateSalary(data.salaryFrom(), data.salaryTo());
    String currency = data.currency() == null ? null : required(data.currency(), "Currency");
    return new VacancyData(
        source,
        externalId,
        title,
        employerName,
        area,
        data.salaryFrom(),
        data.salaryTo(),
        currency,
        url,
        data.publishedAt());
  }

  private static void validateSalary(BigDecimal from, BigDecimal to) {
    if ((from != null && from.signum() < 0) || (to != null && to.signum() < 0)) {
      throw new VacancyValidationException("Salary must be non-negative");
    }
    if (from != null && to != null && from.compareTo(to) > 0) {
      throw new VacancyValidationException("Salary from must not exceed salary to");
    }
  }

  private static String required(String value, String field) {
    if (value == null || value.isBlank()) {
      throw new VacancyValidationException(field + " must not be blank");
    }
    return value.strip();
  }

  private static void requireId(UUID id) {
    if (id == null) {
      throw new VacancyValidationException("ID is required");
    }
  }
}
