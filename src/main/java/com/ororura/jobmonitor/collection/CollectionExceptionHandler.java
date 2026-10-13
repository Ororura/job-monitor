package com.ororura.jobmonitor.collection;

import com.ororura.jobmonitor.search.SearchNotFoundException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(assignableTypes = CollectionController.class)
public class CollectionExceptionHandler {
  @ExceptionHandler({CollectionRunNotFoundException.class, SearchNotFoundException.class})
  ProblemDetail notFound(RuntimeException exception) {
    return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, exception.getMessage());
  }

  @ExceptionHandler(CollectionConflictException.class)
  ProblemDetail conflict(CollectionConflictException exception) {
    return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, exception.getMessage());
  }

  @ExceptionHandler(CollectionValidationException.class)
  ProblemDetail invalid(CollectionValidationException exception) {
    return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, exception.getMessage());
  }
}
