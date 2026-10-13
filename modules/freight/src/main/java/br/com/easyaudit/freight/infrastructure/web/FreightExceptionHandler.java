package br.com.easyaudit.freight.infrastructure.web;

import br.com.easyaudit.freight.application.FreightException;
import br.com.easyaudit.freight.application.TableValidationException;
import br.com.easyaudit.freight.domain.InvalidShipmentException;
import br.com.easyaudit.platform.web.Problems;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(basePackages = "br.com.easyaudit.freight")
@Order(Ordered.HIGHEST_PRECEDENCE)
class FreightExceptionHandler {

    @ExceptionHandler(InvalidShipmentException.class)
    ResponseEntity<ProblemDetail> invalidShipment(InvalidShipmentException ex) {
        var errors = ex.issues().stream().map(i -> new Problems.FieldError(i.field(), i.message())).toList();
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_CONTENT).body(Problems.of(
                HttpStatus.UNPROCESSABLE_CONTENT, "INVALID_SHIPMENT", "Entradas do cálculo inválidas.", errors));
    }

    @ExceptionHandler(TableValidationException.class)
    ResponseEntity<ProblemDetail> validation(TableValidationException ex) {
        var errors = ex.errors().stream().map(e -> new Problems.FieldError(e.location(), e.message())).toList();
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_CONTENT).body(Problems.of(
                HttpStatus.UNPROCESSABLE_CONTENT, "VALIDATION_ERROR", ex.getMessage(), errors));
    }

    @ExceptionHandler(FreightException.class)
    ResponseEntity<ProblemDetail> handle(FreightException ex) {
        HttpStatus status = switch (ex.kind()) {
            case NOT_FOUND -> HttpStatus.NOT_FOUND;
            case CONFLICT -> HttpStatus.CONFLICT;
            case INVALID -> HttpStatus.UNPROCESSABLE_CONTENT;
        };
        return ResponseEntity.status(status).body(Problems.of(status, ex.code(), ex.getMessage()));
    }
}
