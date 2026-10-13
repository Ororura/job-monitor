package com.ororura.jobmonitor.collection;

import java.util.List;

public record CollectionRunPage(
    List<CollectionRun> content, int page, int size, long totalElements) {
  public CollectionRunPage {
    content = List.copyOf(content);
  }
}
