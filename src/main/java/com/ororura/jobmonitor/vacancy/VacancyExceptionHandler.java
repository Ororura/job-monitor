package com.ororura.jobmonitor.vacancy;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(assignableTypes = VacancyController.class)
public class VacancyExceptionHandler {

  @ExceptionHandler(VacancyNotFoundException.class)
  ProblemDetail notFound(VacancyNotFoundException exception) {
    return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, exception.getMessage());
  }

  @ExceptionHandler(VacancyValidationException.class)
  ProblemDetail invalidRequest(VacancyValidationException exception) {
    return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, exception.getMessage());
  }
}
