package com.ororura.jobmonitor.source;

public class BrowserSourceException extends RuntimeException {
  public enum Reason {
    DISABLED,
    CONFIGURATION,
    LAUNCH,
    NAVIGATION,
    TIMEOUT,
    BLOCKED,
    STRUCTURE
  }

  private static final long serialVersionUID = 1L;
  private final Reason failureReason;

  public BrowserSourceException(Reason reason) {
    // Only fixed diagnostic codes may reach logs and persisted run history.
    super("Browser collection failed: " + reason.name());
    this.failureReason = reason;
  }

  public Reason reason() {
    return failureReason;
  }
}
