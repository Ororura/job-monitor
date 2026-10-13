package com.ororura.jobmonitor.search;

import java.util.UUID;

public class SearchNotFoundException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  public SearchNotFoundException(UUID id) {
    super("Search query not found: " + id);
  }
}
