package br.com.easyaudit.settlement.infrastructure.web;

import br.com.easyaudit.platform.web.Problems;
import br.com.easyaudit.settlement.application.SettlementException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(basePackages = "br.com.easyaudit.settlement")
@Order(Ordered.HIGHEST_PRECEDENCE)
class SettlementExceptionHandler {

    @ExceptionHandler(SettlementException.class)
    ResponseEntity<ProblemDetail> handle(SettlementException ex) {
        HttpStatus status = switch (ex.kind()) {
            case NOT_FOUND -> HttpStatus.NOT_FOUND;
            case CONFLICT -> HttpStatus.CONFLICT;
            case INVALID -> HttpStatus.UNPROCESSABLE_CONTENT;
        };
        ProblemDetail problem = ex.issues().isEmpty()
                ? Problems.of(status, ex.code(), ex.getMessage())
                : Problems.of(status, ex.code(), ex.getMessage(),
                        ex.issues().stream().map(i -> new Problems.FieldError(i.field(), i.message())).toList());
        return ResponseEntity.status(status).body(problem);
    }
}
