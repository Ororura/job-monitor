package com.ororura.jobmonitor.collection;

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
  ThreadPoolTaskExecutor collectionWorker(CollectionProperties properties) {
    var worker = new ThreadPoolTaskExecutor();
    worker.setCorePoolSize(1);
    worker.setMaxPoolSize(1);
    worker.setQueueCapacity(properties.queueCapacity());
    worker.setThreadNamePrefix("collection-worker-");
    worker.setWaitForTasksToCompleteOnShutdown(true);
    worker.setAwaitTerminationSeconds(10);
    return worker;
  }
}
