package com.ororura.jobmonitor.collection;

import com.ororura.jobmonitor.search.SearchService;
import java.time.Clock;
import java.time.Instant;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class CollectionScheduler {
  private final SearchService searches;
  private final CollectionDispatcher dispatcher;
  private final CollectionProperties properties;
  private final Clock clock;

  CollectionScheduler(
      SearchService searches,
      CollectionDispatcher dispatcher,
      CollectionProperties properties,
      Clock clock) {
    this.searches = searches;
    this.dispatcher = dispatcher;
    this.properties = properties;
    this.clock = clock;
  }

  @Scheduled(
      fixedDelayString = "${app.collection.poll-interval}",
      initialDelayString = "${app.collection.poll-interval}")
  public void poll() {
    if (!properties.enabled()) {
      return;
    }
    Instant now = clock.instant();
    searches.findAll().stream()
        .filter(query -> CollectionService.isDue(query, now))
        .forEach(query -> dispatcher.submit(query.id(), true));
  }
}
