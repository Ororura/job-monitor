package com.ororura.jobmonitor.collection;

import java.io.Serial;

public class CollectionValidationException extends RuntimeException {
  @Serial private static final long serialVersionUID = 1L;

  public CollectionValidationException(String message) {
    super(message);
  }
}
