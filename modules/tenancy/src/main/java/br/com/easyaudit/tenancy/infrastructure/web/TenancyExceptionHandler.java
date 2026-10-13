package br.com.easyaudit.tenancy.infrastructure.web;

import java.util.List;

import br.com.easyaudit.platform.web.Problems;
import br.com.easyaudit.tenancy.application.TenancyConflictException;
import br.com.easyaudit.tenancy.application.TenantNotFoundException;
import br.com.easyaudit.tenancy.domain.InvalidCnpjException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(basePackages = "br.com.easyaudit.tenancy")
@Order(Ordered.HIGHEST_PRECEDENCE)
class TenancyExceptionHandler {

    @ExceptionHandler(TenancyConflictException.class)
    ResponseEntity<ProblemDetail> conflict(TenancyConflictException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(Problems.of(HttpStatus.CONFLICT, ex.code(), ex.getMessage()));
    }

    @ExceptionHandler(InvalidCnpjException.class)
    ResponseEntity<ProblemDetail> invalidCnpj(InvalidCnpjException ex) {
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_CONTENT).body(Problems.of(
                HttpStatus.UNPROCESSABLE_CONTENT, "INVALID_CNPJ", ex.getMessage(),
                List.of(new Problems.FieldError("cnpj", ex.getMessage()))));
    }

    @ExceptionHandler(TenantNotFoundException.class)
    ResponseEntity<ProblemDetail> notFound(TenantNotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(Problems.of(HttpStatus.NOT_FOUND, "NOT_FOUND", ex.getMessage()));
    }
}
