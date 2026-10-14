package com.ororura.jobmonitor.source;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class PlaywrightPropertiesTest {
  @ParameterizedTest
  @ValueSource(
      strings = {
        "https://hh.ru/search",
        "https://spb.hh.ru/search",
        "file:///tmp/jobs",
        "https://user:secret@example.org/jobs",
        "https://example.org/jobs?q=x",
        "https://example.org/jobs#fragment"
      })
  void rejectsUnpermittedEndpoints(String url) {
    assertThatThrownBy(() -> properties(true, URI.create(url), true, 1, Duration.ofSeconds(1)))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void requiresExplicitAccessConfirmationAndUrl() {
    assertThatThrownBy(
            () ->
                properties(
                    true, URI.create("https://example.org/jobs"), false, 1, Duration.ofSeconds(1)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> properties(true, null, true, 1, Duration.ofSeconds(1)))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void rejectsUnboundedConfiguration() {
    assertThatThrownBy(() -> properties(false, null, false, 0, Duration.ofSeconds(1)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> properties(false, null, false, 1, Duration.ZERO))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> properties(false, null, false, 1, Duration.ofNanos(1)))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void acceptsConfirmedHhSearchWithQueryAndRejectsMoreThan100Pages() {
    var uri = URI.create("https://spb.hh.ru/search/vacancy?area=2&order_by=publication_time");
    var enabled = properties(true, uri, true, 100, Duration.ofSeconds(1));
    org.assertj.core.api.Assertions.assertThat(enabled.format())
        .isEqualTo(PlaywrightProperties.HtmlFormat.HH);
    assertThatThrownBy(() -> properties(true, uri, false, 100, Duration.ofSeconds(1)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> properties(true, uri, true, 101, Duration.ofSeconds(1)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                properties(
                    true,
                    URI.create("http://spb.hh.ru/search/vacancy"),
                    true,
                    100,
                    Duration.ofSeconds(1)))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private static PlaywrightProperties properties(
      boolean enabled, URI url, boolean confirmed, int pages, Duration timeout) {
    return new PlaywrightProperties(
        enabled, true, timeout, Duration.ofSeconds(1), pages, 100, url, confirmed);
  }
}
