package com.ororura.jobmonitor.vacancy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.ororura.jobmonitor.search.SearchNotFoundException;
import com.ororura.jobmonitor.search.SearchRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class VacancyServiceTest {

  private static final Instant NOW = Instant.parse("2026-10-13T10:00:00.123456789Z");
  private static final String SORT = "firstSeenAt,desc";
  private static final String JAVA = "Java";
  private static final String EMPLOYER = "Employer";
  private static final String AREA = "Area";
  private static final String URL = "https://hh.ru/vacancy/1";

  private final VacancyRepository repository = mock(VacancyRepository.class);
  private final SearchRepository searchRepository = mock(SearchRepository.class);
  private final VacancyService service =
      new VacancyService(repository, searchRepository, Clock.fixed(NOW, ZoneOffset.UTC));

  @Test
  void normalizesSourceDataAndUsesInjectedUtcClock() {
    VacancyData input =
        new VacancyData(
            " HH ",
            " 1 ",
            " Java ",
            " Employer ",
            " Area ",
            new BigDecimal("100.01"),
            new BigDecimal("200.02"),
            " RUB ",
            " https://hh.ru/vacancy/1 ",
            NOW);
    VacancyData expected =
        new VacancyData(
            "HH", "1", JAVA, EMPLOYER, AREA, input.salaryFrom(), input.salaryTo(), "RUB", URL, NOW);
    Vacancy vacancy = vacancy();
    when(repository.upsert(expected, NOW, List.of())).thenReturn(vacancy);

    assertThat(service.save(input)).isEqualTo(vacancy);

    verify(repository).upsert(expected, NOW, List.of());
    verifyNoInteractions(searchRepository);
  }

  @ParameterizedTest
  @MethodSource("invalidData")
  void rejectsInvalidSingleAndBatchDataBeforePersistence(VacancyData data) {
    assertThatThrownBy(() -> service.save(data)).isInstanceOf(VacancyValidationException.class);
    assertThatThrownBy(() -> service.saveAll(Arrays.asList(validData(), data)))
        .isInstanceOf(VacancyValidationException.class);
    verifyNoInteractions(repository, searchRepository);
  }

  @Test
  void rejectsMissingBatchAndSearchWithoutPersisting() {
    assertThatThrownBy(() -> service.saveAll(null)).isInstanceOf(VacancyValidationException.class);
    UUID missingSearch = UUID.randomUUID();
    when(searchRepository.findById(missingSearch)).thenReturn(Optional.empty());
    assertThatThrownBy(() -> service.save(validData(), missingSearch))
        .isInstanceOf(SearchNotFoundException.class);
    assertThatThrownBy(() -> service.saveAll(List.of(validData()), missingSearch))
        .isInstanceOf(SearchNotFoundException.class);
    verifyNoInteractions(repository);
  }

  @Test
  void batchUsesOneObservationTimeForAllItems() {
    when(repository.upsert(any(), any(), any())).thenReturn(vacancy());
    assertThat(service.saveAll(List.of(validData(), validData()))).hasSize(2);
    verify(repository, org.mockito.Mockito.times(2)).upsert(validData(), NOW, List.of());
  }

  @Test
  void readsVacancyAndChangesOnlyStatus() {
    Vacancy vacancy = vacancy();
    when(repository.findById(vacancy.id())).thenReturn(Optional.of(vacancy));
    when(repository.updateStatus(vacancy.id(), VacancyStatus.SAVED)).thenReturn(true);

    assertThat(service.findById(vacancy.id())).isEqualTo(vacancy);
    assertThat(service.changeStatus(vacancy.id(), VacancyStatus.SAVED)).isEqualTo(vacancy);

    verify(repository).updateStatus(vacancy.id(), VacancyStatus.SAVED);
  }

  @Test
  void reportsMissingVacancyOnReadAndStatusChange() {
    UUID id = UUID.randomUUID();
    when(repository.findById(id)).thenReturn(Optional.empty());
    assertThatThrownBy(() -> service.findById(id)).isInstanceOf(VacancyNotFoundException.class);
    assertThatThrownBy(() -> service.changeStatus(id, VacancyStatus.NEW))
        .isInstanceOf(VacancyNotFoundException.class);
  }

  @Test
  void rejectsNullIdsAndStatus() {
    assertThatThrownBy(() -> service.findById(null)).isInstanceOf(VacancyValidationException.class);
    assertThatThrownBy(() -> service.changeStatus(null, VacancyStatus.NEW))
        .isInstanceOf(VacancyValidationException.class);
    assertThatThrownBy(() -> service.changeStatus(UUID.randomUUID(), null))
        .isInstanceOf(VacancyValidationException.class);
    assertThatThrownBy(() -> service.linkToSearch(UUID.randomUUID(), null))
        .isInstanceOf(VacancyValidationException.class);
    verifyNoInteractions(repository, searchRepository);
  }

  @ParameterizedTest
  @MethodSource("invalidPages")
  void rejectsInvalidPaginationAndSort(int page, int size, String sort) {
    assertThatThrownBy(() -> service.findAll(null, null, null, page, size, sort))
        .isInstanceOf(VacancyValidationException.class);
    verifyNoInteractions(repository);
  }

  @Test
  void normalizesSourceFilterAndPassesBoundFiltersToRepository() {
    UUID searchId = UUID.randomUUID();
    VacancyPage expected = new VacancyPage(List.of(vacancy()), 1, 10, 11);
    when(repository.findAll(VacancyStatus.SAVED, "HH", searchId, 1, 10, true)).thenReturn(expected);

    assertThat(service.findAll(VacancyStatus.SAVED, " HH ", searchId, 1, 10, "firstSeenAt,asc"))
        .isEqualTo(expected);
    assertThatThrownBy(() -> service.findAll(null, " ", null, 0, 20, SORT))
        .isInstanceOf(VacancyValidationException.class);
  }

  static Stream<Arguments> invalidPages() {
    return Stream.of(
        Arguments.of(-1, 20, SORT),
        Arguments.of(0, 0, SORT),
        Arguments.of(0, -1, SORT),
        Arguments.of(0, 101, SORT),
        Arguments.of(0, 20, null),
        Arguments.of(0, 20, "title,asc"),
        Arguments.of(0, 20, "firstSeenAt,invalid"));
  }

  static Stream<VacancyData> invalidData() {
    return Stream.of(
        null,
        new VacancyData(null, "1", JAVA, EMPLOYER, AREA, null, null, null, URL, null),
        new VacancyData(" ", "1", JAVA, EMPLOYER, AREA, null, null, null, URL, null),
        new VacancyData("HH", null, JAVA, EMPLOYER, AREA, null, null, null, URL, null),
        new VacancyData("HH", " ", JAVA, EMPLOYER, AREA, null, null, null, URL, null),
        new VacancyData("HH", "1", null, EMPLOYER, AREA, null, null, null, URL, null),
        new VacancyData("HH", "1", " ", EMPLOYER, AREA, null, null, null, URL, null),
        new VacancyData("HH", "1", JAVA, null, AREA, null, null, null, URL, null),
        new VacancyData("HH", "1", JAVA, " ", AREA, null, null, null, URL, null),
        new VacancyData("HH", "1", JAVA, EMPLOYER, null, null, null, null, URL, null),
        new VacancyData("HH", "1", JAVA, EMPLOYER, " ", null, null, null, URL, null),
        new VacancyData("HH", "1", JAVA, EMPLOYER, AREA, null, null, null, null, null),
        new VacancyData("HH", "1", JAVA, EMPLOYER, AREA, null, null, null, " ", null),
        new VacancyData("HH", "1", JAVA, EMPLOYER, AREA, null, null, null, "not a URL", null),
        new VacancyData("HH", "1", JAVA, EMPLOYER, AREA, null, null, null, "/relative", null),
        new VacancyData(
            "HH", "1", JAVA, EMPLOYER, AREA, null, null, null, "file:///tmp/file", null),
        new VacancyData(
            "HH",
            "1",
            JAVA,
            EMPLOYER,
            AREA,
            null,
            null,
            null,
            "https://user:password@example.com",
            null),
        new VacancyData(
            "HH", "1", JAVA, EMPLOYER, AREA, new BigDecimal("-1"), null, null, URL, null),
        new VacancyData(
            "HH", "1", JAVA, EMPLOYER, AREA, null, new BigDecimal("-1"), null, URL, null),
        new VacancyData(
            "HH", "1", JAVA, EMPLOYER, AREA, BigDecimal.TEN, BigDecimal.ONE, null, URL, null),
        new VacancyData("HH", "1", JAVA, EMPLOYER, AREA, null, null, " ", URL, null));
  }

  private static VacancyData validData() {
    return new VacancyData("HH", "1", JAVA, EMPLOYER, AREA, null, null, null, URL, null);
  }

  private static Vacancy vacancy() {
    return new Vacancy(
        UUID.randomUUID(),
        "HH",
        "1",
        JAVA,
        EMPLOYER,
        AREA,
        null,
        null,
        null,
        URL,
        null,
        NOW,
        NOW,
        VacancyStatus.SAVED);
  }
}
