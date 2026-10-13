package com.ororura.jobmonitor.web;

import com.ororura.jobmonitor.collection.CollectionRunNotFoundException;
import com.ororura.jobmonitor.collection.CollectionValidationException;
import com.ororura.jobmonitor.search.SearchNotFoundException;
import com.ororura.jobmonitor.search.SearchValidationException;
import com.ororura.jobmonitor.source.PlaywrightProperties;
import com.ororura.jobmonitor.vacancy.VacancyNotFoundException;
import com.ororura.jobmonitor.vacancy.VacancyStatus;
import com.ororura.jobmonitor.vacancy.VacancyValidationException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@ControllerAdvice(basePackages = "com.ororura.jobmonitor.web")
public class WebAdvice {
  private final PlaywrightProperties playwright;

  public WebAdvice(PlaywrightProperties playwright) {
    this.playwright = playwright;
  }

  @ModelAttribute
  void common(Model model, HttpServletRequest request) {
    model.addAttribute("csrf", WebConfiguration.csrf(request));
    model.addAttribute("path", request.getRequestURI());
    model.addAttribute(
        "playwrightReady",
        playwright.enabled() && playwright.searchUrl() != null && playwright.accessConfirmed());
    model.addAttribute("statuses", VacancyStatus.values());
  }

  static boolean htmx(HttpServletRequest request) {
    return "true".equals(request.getHeader("HX-Request"));
  }

  @ExceptionHandler({
    SearchNotFoundException.class,
    VacancyNotFoundException.class,
    CollectionRunNotFoundException.class
  })
  String missing(HttpServletRequest request, HttpServletResponse response, Model model) {
    return error(404, "Запись не найдена. Возможно, она была удалена.", request, response, model);
  }

  @ExceptionHandler({
    SearchValidationException.class,
    VacancyValidationException.class,
    CollectionValidationException.class,
    MethodArgumentTypeMismatchException.class
  })
  String invalid(HttpServletRequest request, HttpServletResponse response, Model model) {
    return error(
        400, "Некорректные параметры. Проверьте фильтры и поля формы.", request, response, model);
  }

  @ExceptionHandler(WebForbiddenException.class)
  String forbidden(HttpServletRequest request, HttpServletResponse response, Model model) {
    return error(
        403, "Форма устарела. Обновите страницу и повторите действие.", request, response, model);
  }

  @ExceptionHandler(Exception.class)
  String internal(
      Exception exception, HttpServletRequest request, HttpServletResponse response, Model model) {
    org.slf4j.LoggerFactory.getLogger(WebAdvice.class)
        .error("Web request failed ({})", exception.getClass().getSimpleName());
    return error(
        500, "Не удалось выполнить действие. Попробуйте ещё раз.", request, response, model);
  }

  private static String error(
      int status,
      String message,
      HttpServletRequest request,
      HttpServletResponse response,
      Model model) {
    response.setStatus(status);
    model.addAttribute("message", message);
    model.addAttribute("error", true);
    if (htmx(request)) {
      response.setHeader("HX-Retarget", "#notice");
      response.setHeader("HX-Reswap", "innerHTML");
      return "fragments/notice :: notice";
    }
    return "error";
  }
}
