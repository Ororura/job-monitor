package com.ororura.jobmonitor.search;

import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/search-queries")
public class SearchController {

  private final SearchService service;

  SearchController(SearchService service) {
    this.service = service;
  }

  @GetMapping
  public List<SearchQueryResponse> list() {
    return service.findAll().stream().map(SearchQueryResponse::from).toList();
  }

  @GetMapping("/{id}")
  public SearchQueryResponse get(@PathVariable UUID id) {
    return SearchQueryResponse.from(service.findById(id));
  }

  @PostMapping
  public ResponseEntity<SearchQueryResponse> create(@RequestBody SearchQueryRequest request) {
    SearchQueryResponse response = SearchQueryResponse.from(service.create(request));
    return ResponseEntity.created(URI.create("/api/v1/search-queries/" + response.id()))
        .body(response);
  }

  @PutMapping("/{id}")
  public SearchQueryResponse update(
      @PathVariable UUID id, @RequestBody SearchQueryRequest request) {
    return SearchQueryResponse.from(service.update(id, request));
  }

  @DeleteMapping("/{id}")
  public ResponseEntity<Void> delete(@PathVariable UUID id) {
    service.delete(id);
    return ResponseEntity.noContent().build();
  }
}
