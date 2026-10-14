package com.ororura.jobmonitor.web;

import com.ororura.jobmonitor.collection.CollectionRunStatus;
import com.ororura.jobmonitor.collection.CollectionService;
import com.ororura.jobmonitor.search.SearchService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;

@Controller
public class CollectionPageController {
  private final CollectionService collections;
  private final SearchService searches;

  CollectionPageController(CollectionService collections, SearchService searches) {
    this.collections = collections;
    this.searches = searches;
  }

  @GetMapping("/collections")
  String list(
      @RequestParam(required = false) UUID searchQueryId,
      @RequestParam(required = false) CollectionRunStatus status,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size,
      Model model,
      HttpServletRequest request) {
    var result = collections.findAll(searchQueryId, status, page, size);
    model.addAttribute("result", result);
    model.addAttribute("searches", searches.findAll());
    model.addAttribute("runStatuses", CollectionRunStatus.values());
    model.addAttribute("status", status);
    model.addAttribute("searchQueryId", searchQueryId);
    model.addAttribute(
        "running",
        result.content().stream().anyMatch(r -> r.status() == CollectionRunStatus.RUNNING));
    return WebAdvice.htmx(request) ? "collections/list :: results" : "collections/list";
  }

  @GetMapping("/collections/{id}")
  String detail(@PathVariable UUID id, Model model, HttpServletRequest request) {
    model.addAttribute("run", collections.findById(id));
    return WebAdvice.htmx(request) ? "collections/detail :: detail" : "collections/detail";
  }
}
