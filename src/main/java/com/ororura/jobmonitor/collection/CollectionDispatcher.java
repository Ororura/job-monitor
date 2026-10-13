package com.ororura.jobmonitor.collection;

import com.ororura.jobmonitor.search.SearchService;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.support.DelegatingErrorHandlingRunnable;
import org.springframework.stereotype.Service;

@Service
public class CollectionDispatcher {
  private static final Logger LOGGER = LoggerFactory.getLogger(CollectionDispatcher.class);
  private final Set<UUID> pending = ConcurrentHashMap.newKeySet();
  private final SearchService searches;
  private final CollectionService service;
  private final TaskExecutor worker;

  CollectionDispatcher(
      SearchService searches,
      CollectionService service,
      @Qualifier("collectionWorker") TaskExecutor worker) {
    this.searches = searches;
    this.service = service;
    this.worker = worker;
  }

  public void runManually(UUID searchId) {
    searches.findById(searchId);
    if (!submit(searchId, false)) {
      throw new CollectionConflictException(
          "Search is already queued/running or collection queue is full");
    }
  }

  boolean submit(UUID searchId, boolean automatic) {
    if (!pending.add(searchId)) {
      return false;
    }
    try {
      worker.execute(
          () -> {
            try {
              new DelegatingErrorHandlingRunnable(
                      () -> service.collect(searchId, automatic),
                      exception -> {
                        if (exception instanceof Error fatal) {
                          throw fatal;
                        }
                        LOGGER.warn(
                            "Collection for {} could not be finalized ({})",
                            searchId,
                            exception.getClass().getSimpleName());
                      })
                  .run();
            } finally {
              pending.remove(searchId);
            }
          });
      return true;
    } catch (RejectedExecutionException exception) {
      pending.remove(searchId);
      return false;
    }
  }
}
