package com.ororura.jobmonitor.search;

public record SearchQueryRequest(
    String name, String queryText, Integer intervalMinutes, Boolean enabled, String source) {
  public SearchQueryRequest(
      String name, String queryText, Integer intervalMinutes, Boolean enabled) {
    this(name, queryText, intervalMinutes, enabled, null);
  }
}
