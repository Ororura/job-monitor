package com.ororura.jobmonitor.collection;

public class CollectionConflictException extends RuntimeException {
  private static final long serialVersionUID = 1L;

  public CollectionConflictException(String message) {
    super(message);
  }
}
