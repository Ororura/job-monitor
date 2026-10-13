package com.ororura.jobmonitor.search;

public record SearchQueryRequest(
    String name, String queryText, Integer intervalMinutes, Boolean enabled) {}
