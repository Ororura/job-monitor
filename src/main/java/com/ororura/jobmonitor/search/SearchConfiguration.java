package com.ororura.jobmonitor.search;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class SearchConfiguration {

  @Bean
  Clock clock() {
    return Clock.systemUTC();
  }
}
