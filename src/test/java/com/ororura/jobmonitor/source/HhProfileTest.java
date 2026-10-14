package com.ororura.jobmonitor.source;

import static org.assertj.core.api.Assertions.assertThat;

import com.ororura.jobmonitor.collection.CollectionProperties;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest
@ActiveProfiles("hh")
class HhProfileTest {
  @TempDir static Path directory;

  @DynamicPropertySource
  static void database(DynamicPropertyRegistry registry) {
    registry.add("job-monitor.data-directory", () -> directory.toString());
  }

  @Test
  void bindsOptInProfileWithoutEnablingScheduler(
      @Autowired PlaywrightProperties playwright, @Autowired CollectionProperties collection) {
    assertThat(playwright.enabled()).isTrue();
    assertThat(playwright.accessConfirmed()).isTrue();
    assertThat(playwright.format()).isEqualTo(PlaywrightProperties.HtmlFormat.HH);
    assertThat(playwright.maxPages()).isEqualTo(100);
    assertThat(playwright.maxVacancies()).isEqualTo(5000);
    assertThat(playwright.searchUrl().getHost()).isEqualTo("spb.hh.ru");
    assertThat(playwright.searchUrl().getRawQuery())
        .contains("area=2", "order_by=publication_time");
    assertThat(collection.enabled()).isFalse();
  }
}
