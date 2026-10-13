package com.ororura.jobmonitor.collection;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("app.collection")
public record CollectionProperties(boolean enabled, Duration pollInterval, int queueCapacity) {
  public CollectionProperties {
    if (pollInterval == null || pollInterval.isNegative() || pollInterval.isZero()) {
      throw new IllegalArgumentException("Collection poll interval must be positive");
    }
    if (queueCapacity <= 0) {
      throw new IllegalArgumentException("Collection queue capacity must be positive");
    }
  }
}
