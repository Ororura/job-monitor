package com.ororura.jobmonitor.search;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(assignableTypes = SearchController.class)
public class SearchExceptionHandler {

  @ExceptionHandler(SearchNotFoundException.class)
  ProblemDetail notFound(SearchNotFoundException exception) {
    return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, exception.getMessage());
  }

  @ExceptionHandler(SearchValidationException.class)
  ProblemDetail invalidRequest(SearchValidationException exception) {
    return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, exception.getMessage());
  }
}
