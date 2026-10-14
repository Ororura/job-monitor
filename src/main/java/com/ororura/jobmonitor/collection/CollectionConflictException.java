package com.ororura.jobmonitor.collection;

import java.io.Serial;

public class CollectionConflictException extends RuntimeException {
  @Serial private static final long serialVersionUID = 1L;

  public CollectionConflictException(String message) {
    super(message);
  }
}
