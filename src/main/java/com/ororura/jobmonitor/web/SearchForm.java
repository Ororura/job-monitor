package com.ororura.jobmonitor.web;

import com.ororura.jobmonitor.search.SearchQuery;
import com.ororura.jobmonitor.search.SearchQueryRequest;

public record SearchForm(
    String name, String queryText, String intervalMinutes, Boolean enabled, String source) {
  public SearchForm {
    enabled = Boolean.TRUE.equals(enabled);
  }

  static SearchForm empty() {
    return new SearchForm("", "", "30", false, "FAKE");
  }

  static SearchForm from(SearchQuery query) {
    return new SearchForm(
        query.name(),
        query.queryText(),
        Integer.toString(query.intervalMinutes()),
        query.enabled(),
        query.source());
  }

  SearchQueryRequest request() {
    return new SearchQueryRequest(name, queryText, interval(), enabled, source);
  }

  private Integer interval() {
    try {
      return Integer.valueOf(intervalMinutes);
    } catch (NumberFormatException exception) {
      return null;
    }
  }
}
