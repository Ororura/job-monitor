package com.ororura.jobmonitor.collection;

import java.util.UUID;

public class CollectionRunNotFoundException extends RuntimeException {
  private static final long serialVersionUID = 1L;

  public CollectionRunNotFoundException(UUID id) {
    super("Collection run not found: " + id);
  }
}
