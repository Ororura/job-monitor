package com.ororura.jobmonitor.collection;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;

final class CollectionTestClock extends Clock {
  static final Instant START = Instant.parse("2026-10-13T10:00:00.123456789Z");
  private final AtomicReference<Instant> now = new AtomicReference<>(START);

  void set(Instant instant) {
    now.set(instant);
  }

  @Override
  public ZoneId getZone() {
    return ZoneOffset.UTC;
  }

  @Override
  public Clock withZone(ZoneId zone) {
    return Clock.fixed(instant(), zone);
  }

  @Override
  public Instant instant() {
    return now.get();
  }
}
