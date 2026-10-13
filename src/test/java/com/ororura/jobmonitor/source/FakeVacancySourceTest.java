package com.ororura.jobmonitor.source;

import static org.assertj.core.api.Assertions.assertThat;

import com.ororura.jobmonitor.search.SearchQuery;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class FakeVacancySourceTest {
  @Test
  void returnsDeterministicRepeatedIdentitiesAcrossSearches() {
    var source = new FakeVacancySource();
    Instant now = Instant.parse("2026-10-13T10:00:00Z");
    var first =
        new SearchQuery(
            UUID.randomUUID(), "Java", "(Java OR Kotlin) AND NOT senior", 30, true, now, now, null);
    var second = new SearchQuery(UUID.randomUUID(), "Kotlin", "Kotlin", 60, false, now, now, null);
    var data = source.fetch(first);
    assertThat(data).hasSize(3).isEqualTo(source.fetch(first)).isEqualTo(source.fetch(second));
    assertThat(data.get(0)).isEqualTo(data.get(2));
    assertThat(data).allMatch(vacancy -> vacancy.source().equals(source.source()));
    assertThat(data.stream().map(item -> item.externalId()).distinct()).hasSize(2);
  }
}
