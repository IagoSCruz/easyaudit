package br.com.easyaudit.documents.infrastructure.web;

import br.com.easyaudit.documents.application.DocumentNotFoundException;
import br.com.easyaudit.platform.web.Problems;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(basePackages = "br.com.easyaudit.documents")
@Order(Ordered.HIGHEST_PRECEDENCE)
class DocumentsExceptionHandler {

    @ExceptionHandler(DocumentNotFoundException.class)
    ResponseEntity<ProblemDetail> notFound(DocumentNotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(Problems.of(HttpStatus.NOT_FOUND, "NOT_FOUND", ex.getMessage()));
    }
}
