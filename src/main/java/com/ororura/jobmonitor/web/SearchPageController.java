package com.ororura.jobmonitor.web;

import com.ororura.jobmonitor.collection.CollectionConflictException;
import com.ororura.jobmonitor.collection.CollectionDispatcher;
import com.ororura.jobmonitor.collection.CollectionRunRepository;
import com.ororura.jobmonitor.search.SearchQueryRequest;
import com.ororura.jobmonitor.search.SearchService;
import com.ororura.jobmonitor.search.SearchValidationException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
public class SearchPageController {
  private static final String SEARCH_PATH = "/searches";
  private static final String MESSAGE = "message";
  private final SearchService searches;
  private final CollectionDispatcher dispatcher;
  private final CollectionRunRepository runs;

  SearchPageController(
      SearchService searches, CollectionDispatcher dispatcher, CollectionRunRepository runs) {
    this.searches = searches;
    this.dispatcher = dispatcher;
    this.runs = runs;
  }

  @GetMapping(SEARCH_PATH)
  String list(Model model, HttpServletRequest request) {
    var queries = searches.findAll();
    model.addAttribute("searches", queries);
    model.addAttribute(
        "latestRuns",
        runs.findLatestPerSearch().stream()
            .collect(Collectors.toMap(r -> r.searchQueryId(), r -> r)));
    model.addAttribute(
        "pending",
        queries.stream()
            .filter(q -> dispatcher.isPending(q.id()))
            .map(q -> q.id())
            .collect(Collectors.toSet()));
    return WebAdvice.htmx(request) ? "searches/list :: results" : "searches/list";
  }

  @GetMapping("/searches/new")
  String createForm(Model model, HttpServletRequest request) {
    return form(null, SearchForm.empty(), Map.of(), model, request);
  }

  @GetMapping("/searches/{id}/edit")
  String editForm(@PathVariable UUID id, Model model, HttpServletRequest request) {
    return form(id, SearchForm.from(searches.findById(id)), Map.of(), model, request);
  }

  @PostMapping(SEARCH_PATH)
  String create(
      @ModelAttribute SearchForm form,
      Model model,
      HttpServletRequest request,
      HttpServletResponse response,
      RedirectAttributes flash) {
    return save(null, form, model, request, response, flash);
  }

  @PostMapping("/searches/{id}")
  String update(
      @PathVariable UUID id,
      @ModelAttribute SearchForm form,
      Model model,
      HttpServletRequest request,
      HttpServletResponse response,
      RedirectAttributes flash) {
    return save(id, form, model, request, response, flash);
  }

  private String save(
      UUID id,
      SearchForm form,
      Model model,
      HttpServletRequest request,
      HttpServletResponse response,
      RedirectAttributes flash) {
    try {
      if (id == null) {
        searches.create(form.request());
      } else {
        searches.update(id, form.request());
      }
    } catch (SearchValidationException exception) {
      response.setStatus(422);
      String field =
          exception.getMessage().startsWith("Name")
              ? "name"
              : exception.getMessage().startsWith("Query")
                  ? "queryText"
                  : exception.getMessage().startsWith("Interval") ? "intervalMinutes" : "source";
      String message =
          switch (field) {
            case "name" -> "Укажите название запроса.";
            case "queryText" -> "Укажите поисковое выражение.";
            case "intervalMinutes" -> "Укажите целое число, не меньше 10 минут.";
            default -> "Выберите FAKE или PLAYWRIGHT.";
          };
      return form(id, form, Map.of(field, message), model, request);
    }
    if (WebAdvice.htmx(request)) {
      response.setHeader("HX-Redirect", SEARCH_PATH);
      request.getSession().setAttribute("webMessage", "Поисковый запрос сохранён.");
      return "fragments/notice :: notice";
    }
    flash.addFlashAttribute(MESSAGE, "Поисковый запрос сохранён.");
    return "redirect:/searches";
  }

  private String form(
      UUID id,
      SearchForm form,
      Map<String, String> errors,
      Model model,
      HttpServletRequest request) {
    model.addAttribute("form", form);
    model.addAttribute("id", id);
    model.addAttribute("errors", errors);
    return WebAdvice.htmx(request) ? "searches/form :: form" : "searches/form";
  }

  @PostMapping("/searches/{id}/toggle")
  String toggle(
      @PathVariable UUID id, Model model, HttpServletRequest request, RedirectAttributes flash) {
    var query = searches.findById(id);
    searches.update(
        id,
        new SearchQueryRequest(
            query.name(),
            query.queryText(),
            query.intervalMinutes(),
            !query.enabled(),
            query.source()));
    return changed("Состояние запроса обновлено.", model, request, flash);
  }

  @GetMapping("/searches/{id}/delete")
  String confirmDelete(@PathVariable UUID id, Model model) {
    model.addAttribute("search", searches.findById(id));
    return "searches/delete";
  }

  @PostMapping("/searches/{id}/delete")
  String delete(
      @PathVariable UUID id,
      Model model,
      HttpServletRequest request,
      HttpServletResponse response,
      RedirectAttributes flash) {
    searches.delete(id);
    if (WebAdvice.htmx(request)) {
      response.setHeader("HX-Redirect", SEARCH_PATH);
      request
          .getSession()
          .setAttribute("webMessage", "Запрос удалён. Вакансии и история сохранены.");
      return "fragments/notice :: notice";
    }
    return changed("Запрос удалён. Вакансии и история сохранены.", model, request, flash);
  }

  @PostMapping("/searches/{id}/run")
  String run(
      @PathVariable UUID id,
      Model model,
      HttpServletRequest request,
      HttpServletResponse response,
      RedirectAttributes flash) {
    try {
      dispatcher.runManually(id);
    } catch (CollectionConflictException exception) {
      if (!WebAdvice.htmx(request)) {
        model.addAttribute("error", true);
        response.setStatus(409);
        model.addAttribute(
            MESSAGE, "Запрос уже в очереди или выполняется, либо очередь заполнена.");
        return list(model, request);
      }
      response.setStatus(409);
      model.addAttribute("error", true);
      return changed(
          "Запрос уже в очереди или выполняется, либо очередь заполнена.", model, request, flash);
    }
    if (WebAdvice.htmx(request)) {
      response.setStatus(202);
    }
    return changed(
        "Задание принято в очередь. Состояние обновляется каждые 8 секунд.", model, request, flash);
  }

  private String changed(
      String message, Model model, HttpServletRequest request, RedirectAttributes flash) {
    if (WebAdvice.htmx(request)) {
      model.addAttribute(MESSAGE, message);
      return list(model, request);
    }
    flash.addFlashAttribute(MESSAGE, message);
    return "redirect:/searches";
  }
}
