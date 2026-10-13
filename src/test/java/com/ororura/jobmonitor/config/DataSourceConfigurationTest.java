package com.ororura.jobmonitor.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DataSourceConfigurationTest {

  private static final String LINUX = "Linux";

  @Test
  void usesMacApplicationSupport(@TempDir Path home) {
    assertThat(
            DataSourceConfiguration.resolveDataDirectory(
                "", "Mac OS X", home.toString(), home.resolve("ignored").toString()))
        .isEqualTo(home.resolve("Library/Application Support/job-monitor"));
  }

  @Test
  void usesLinuxUserDataDirectory(@TempDir Path home) {
    assertThat(DataSourceConfiguration.resolveDataDirectory("", LINUX, home.toString(), null))
        .isEqualTo(home.resolve(".local/share/job-monitor"));
    assertThat(DataSourceConfiguration.resolveDataDirectory("", LINUX, home.toString(), ""))
        .isEqualTo(home.resolve(".local/share/job-monitor"));
    assertThat(DataSourceConfiguration.resolveDataDirectory("", LINUX, home.toString(), "relative"))
        .isEqualTo(home.resolve(".local/share/job-monitor"));
  }

  @Test
  void respectsXdgDataHome(@TempDir Path home) {
    Path dataHome = home.resolve("custom data");
    assertThat(
            DataSourceConfiguration.resolveDataDirectory(
                "", LINUX, home.toString(), dataHome.toString()))
        .isEqualTo(dataHome.resolve("job-monitor"));
  }

  @Test
  void explicitDirectoryOverridesPlatformDefaults(@TempDir Path directory) {
    String ignoredDirectory = directory.resolve("ignored").toString();
    assertThat(
            DataSourceConfiguration.resolveDataDirectory(
                directory.toString(), "Mac OS X", ignoredDirectory, null))
        .isEqualTo(directory);
    assertThat(
            DataSourceConfiguration.resolveDataDirectory(
                directory.toString(), LINUX, ignoredDirectory, ignoredDirectory))
        .isEqualTo(directory);
  }
}
