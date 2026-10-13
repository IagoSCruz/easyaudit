package br.com.easyaudit.quotes.infrastructure.web;

import br.com.easyaudit.platform.web.Problems;
import br.com.easyaudit.quotes.application.QuoteNotFoundException;
import br.com.easyaudit.quotes.application.QuoteValidationException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(basePackages = "br.com.easyaudit.quotes")
@Order(Ordered.HIGHEST_PRECEDENCE)
class QuotesExceptionHandler {

    @ExceptionHandler(QuoteValidationException.class)
    ResponseEntity<ProblemDetail> validation(QuoteValidationException ex) {
        var errors = ex.issues().stream().map(i -> new Problems.FieldError(i.field(), i.message())).toList();
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_CONTENT).body(Problems.of(
                HttpStatus.UNPROCESSABLE_CONTENT, "VALIDATION_ERROR", ex.getMessage(), errors));
    }

    @ExceptionHandler(QuoteNotFoundException.class)
    ResponseEntity<ProblemDetail> notFound(QuoteNotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Problems.of(HttpStatus.NOT_FOUND, "NOT_FOUND", ex.getMessage()));
    }
}
