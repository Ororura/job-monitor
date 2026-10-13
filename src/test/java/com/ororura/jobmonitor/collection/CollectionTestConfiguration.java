package com.ororura.jobmonitor.collection;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

@TestConfiguration(proxyBeanMethods = false)
class CollectionTestConfiguration {
  @Bean
  @Primary
  CollectionTestClock collectionTestClock() {
    return new CollectionTestClock();
  }
}
