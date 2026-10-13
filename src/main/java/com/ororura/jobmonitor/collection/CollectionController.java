package com.ororura.jobmonitor.collection;

import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class CollectionController {
  private final CollectionDispatcher dispatcher;
  private final CollectionService service;

  CollectionController(CollectionDispatcher dispatcher, CollectionService service) {
    this.dispatcher = dispatcher;
    this.service = service;
  }

  @PostMapping("/api/v1/search-queries/{id}/run")
  public ResponseEntity<Void> run(@PathVariable UUID id) {
    dispatcher.runManually(id);
    return ResponseEntity.accepted().build();
  }

  @GetMapping("/api/v1/collection-runs")
  public CollectionRunPage list(
      @RequestParam(required = false) UUID searchQueryId,
      @RequestParam(required = false) CollectionRunStatus status,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size) {
    return service.findAll(searchQueryId, status, page, size);
  }

  @GetMapping("/api/v1/collection-runs/{id}")
  public CollectionRun get(@PathVariable UUID id) {
    return service.findById(id);
  }
}
