package com.ororura.jobmonitor.collection;

import com.ororura.jobmonitor.source.PlaywrightProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

@Configuration(proxyBeanMethods = false)
@EnableScheduling
@EnableConfigurationProperties(CollectionProperties.class)
public class CollectionConfiguration {
  @Bean
  ThreadPoolTaskExecutor collectionWorker(
      CollectionProperties properties, PlaywrightProperties browserProperties) {
    var worker = new ThreadPoolTaskExecutor();
    worker.setCorePoolSize(1);
    worker.setMaxPoolSize(1);
    worker.setQueueCapacity(properties.queueCapacity());
    worker.setThreadNamePrefix("collection-worker-");
    worker.setWaitForTasksToCompleteOnShutdown(true);
    worker.setAwaitTerminationSeconds(10);
    if (browserProperties.enabled()) {
      // Queued browser jobs stop immediately on context close. Allow the active bounded browser
      // operation and teardown to finish before SQLite and other dependent beans are destroyed.
      worker.setAwaitTerminationMillis(
          browserProperties
              .navigationTimeout()
              .multipliedBy(2)
              .plus(browserProperties.operationTimeout())
              .plusSeconds(30)
              .toMillis());
    }
    return worker;
  }
}
