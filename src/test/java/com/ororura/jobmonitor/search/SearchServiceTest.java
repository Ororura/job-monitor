package com.ororura.jobmonitor.search;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class SearchServiceTest {

  private static final String JAVA = "Java";

  private static final Instant NOW = Instant.parse("2026-10-13T09:00:00.123456789Z");
  private static final String EXPRESSION =
      "  (Java AND \"Spring Boot\") OR (Kotlin AND NOT \"senior developer\")  ";

  private final SearchRepository repository = mock(SearchRepository.class);
  private final SearchService service =
      new SearchService(repository, Clock.fixed(NOW, ZoneOffset.UTC));

  @Test
  void createsQueryWithoutChangingExpression() {
    SearchQuery created = service.create(new SearchQueryRequest(JAVA, EXPRESSION, 10, true));

    assertThat(created.id()).isNotNull();
    assertThat(created.name()).isEqualTo(JAVA);
    assertThat(created.queryText()).isEqualTo(EXPRESSION);
    assertThat(created.intervalMinutes()).isEqualTo(10);
    assertThat(created.enabled()).isTrue();
    assertThat(created.createdAt()).isEqualTo(NOW);
    assertThat(created.updatedAt()).isEqualTo(NOW);
    assertThat(created.nextRunAt()).isNull();
    verify(repository).insert(created);
  }

  @Test
  void listsAndFindsQueries() {
    SearchQuery query = existingQuery();
    when(repository.findAll()).thenReturn(List.of(query));
    when(repository.findById(query.id())).thenReturn(Optional.of(query));

    assertThat(service.findAll()).containsExactly(query);
    assertThat(service.findById(query.id())).isEqualTo(query);
  }

  @Test
  void updatesEditableFieldsPreservingIdentityAndCompletionAnchorEvenWhenDisabled() {
    SearchQuery existing = existingQuery();
    when(repository.findById(existing.id())).thenReturn(Optional.of(existing));
    SearchQuery expected =
        new SearchQuery(
            existing.id(),
            "Changed",
            EXPRESSION,
            60,
            false,
            existing.createdAt(),
            NOW,
            existing.nextRunAt().plusSeconds(1800));
    when(repository.update(expected)).thenReturn(true);

    assertThat(
            service.update(existing.id(), new SearchQueryRequest("Changed", EXPRESSION, 60, false)))
        .isEqualTo(expected);
    verify(repository).update(expected);
  }

  @Test
  void enablesDisabledQuery() {
    SearchQuery disabled =
        new SearchQuery(UUID.randomUUID(), JAVA, JAVA, 10, false, NOW, NOW, null);
    when(repository.findById(disabled.id())).thenReturn(Optional.of(disabled));
    SearchQuery expected = new SearchQuery(disabled.id(), JAVA, JAVA, 10, true, NOW, NOW, null);
    when(repository.update(expected)).thenReturn(true);

    assertThat(service.update(disabled.id(), new SearchQueryRequest(JAVA, JAVA, 10, true)))
        .isEqualTo(expected);
  }

  @Test
  void changingEnabledIntervalRecalculatesNextRunFromPreviousCompletion() {
    SearchQuery existing = existingQuery();
    when(repository.findById(existing.id())).thenReturn(Optional.of(existing));
    when(repository.update(org.mockito.ArgumentMatchers.any())).thenReturn(true);
    SearchQuery updated =
        service.update(
            existing.id(), new SearchQueryRequest(existing.name(), existing.queryText(), 10, true));
    assertThat(updated.nextRunAt()).isEqualTo(existing.nextRunAt().minusSeconds(1200));
  }

  @Test
  void deletesExistingQuery() {
    UUID id = UUID.randomUUID();
    when(repository.deleteById(id)).thenReturn(true);

    service.delete(id);

    verify(repository).deleteById(id);
  }

  @Test
  void reportsMissingQueryOnReadUpdateAndDelete() {
    UUID id = UUID.randomUUID();
    when(repository.findById(id)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.findById(id)).isInstanceOf(SearchNotFoundException.class);
    assertThatThrownBy(() -> service.update(id, new SearchQueryRequest(JAVA, JAVA, 10, true)))
        .isInstanceOf(SearchNotFoundException.class);
    assertThatThrownBy(() -> service.delete(id)).isInstanceOf(SearchNotFoundException.class);
    verify(repository, never()).update(org.mockito.ArgumentMatchers.any());
  }

  @ParameterizedTest
  @MethodSource("invalidRequests")
  void rejectsInvalidCreateAndUpdateBeforeAccessingRepository(SearchQueryRequest request) {
    assertThatThrownBy(() -> service.create(request)).isInstanceOf(SearchValidationException.class);
    assertThatThrownBy(() -> service.update(UUID.randomUUID(), request))
        .isInstanceOf(SearchValidationException.class);
    verifyNoInteractions(repository);
  }

  static Stream<SearchQueryRequest> invalidRequests() {
    return Stream.of(
        null,
        new SearchQueryRequest(null, JAVA, 10, true),
        new SearchQueryRequest("", JAVA, 10, true),
        new SearchQueryRequest(" \t\n", JAVA, 10, true),
        new SearchQueryRequest(JAVA, null, 10, true),
        new SearchQueryRequest(JAVA, "", 10, true),
        new SearchQueryRequest(JAVA, " \t\n", 10, true),
        new SearchQueryRequest(JAVA, JAVA, null, true),
        new SearchQueryRequest(JAVA, JAVA, -1, true),
        new SearchQueryRequest(JAVA, JAVA, 0, true),
        new SearchQueryRequest(JAVA, JAVA, 9, true),
        new SearchQueryRequest(JAVA, JAVA, 10, null));
  }

  private static SearchQuery existingQuery() {
    Instant createdAt = NOW.minusSeconds(3600);
    return new SearchQuery(
        UUID.randomUUID(), "Original", JAVA, 30, true, createdAt, createdAt, NOW.plusSeconds(600));
  }
}
