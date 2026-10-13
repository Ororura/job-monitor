package com.ororura.jobmonitor.config;

import com.zaxxer.hikari.HikariDataSource;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.jdbc.autoconfigure.DataSourceProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.util.StringUtils;

@Configuration(proxyBeanMethods = false)
public class DataSourceConfiguration {

  @Bean
  @ConfigurationProperties("spring.datasource.hikari")
  HikariDataSource dataSource(
      DataSourceProperties properties,
      Environment environment,
      @Value("${job-monitor.data-directory}") String configuredDirectory)
      throws IOException {
    Path directory =
        resolveDataDirectory(
            configuredDirectory,
            environment.getRequiredProperty("os.name"),
            environment.getRequiredProperty("user.home"),
            environment.getProperty("XDG_DATA_HOME"));
    Files.createDirectories(directory);
    properties.setUrl("jdbc:sqlite:" + directory.resolve("job-monitor.db"));
    return properties.initializeDataSourceBuilder().type(HikariDataSource.class).build();
  }

  static Path resolveDataDirectory(
      String configuredDirectory, String osName, String userHome, String xdgDataHome) {
    if (StringUtils.hasText(configuredDirectory)) {
      return Path.of(configuredDirectory).toAbsolutePath().normalize();
    }
    String operatingSystem = osName.toLowerCase(Locale.ROOT);
    if (operatingSystem.startsWith("mac")) {
      return Path.of(userHome, "Library", "Application Support", "job-monitor");
    }
    if (operatingSystem.startsWith("linux")) {
      Path baseDirectory =
          StringUtils.hasText(xdgDataHome) && Path.of(xdgDataHome).isAbsolute()
              ? Path.of(xdgDataHome)
              : Path.of(userHome, ".local", "share");
      return baseDirectory.resolve("job-monitor");
    }
    throw new IllegalStateException("Set job-monitor.data-directory for this operating system");
  }
}
