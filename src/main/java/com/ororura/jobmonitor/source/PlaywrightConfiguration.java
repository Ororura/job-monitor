package com.ororura.jobmonitor.source;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(PlaywrightProperties.class)
public class PlaywrightConfiguration {}
