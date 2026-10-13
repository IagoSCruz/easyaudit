package br.com.easyaudit.audit.infrastructure.web;

import java.util.Map;

import br.com.easyaudit.audit.application.AuditException;
import br.com.easyaudit.platform.web.Problems;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(basePackages = "br.com.easyaudit.audit")
@Order(Ordered.HIGHEST_PRECEDENCE)
class AuditExceptionHandler {

    @ExceptionHandler(AuditException.class)
    ResponseEntity<ProblemDetail> handle(AuditException ex) {
        HttpStatus status = switch (ex.kind()) {
            case NOT_FOUND -> HttpStatus.NOT_FOUND;
            case CONFLICT -> HttpStatus.CONFLICT;
            case INVALID -> HttpStatus.UNPROCESSABLE_CONTENT;
            case BAD_REQUEST -> HttpStatus.BAD_REQUEST;
        };
        ProblemDetail problem = ex.issues().isEmpty()
                ? Problems.of(status, ex.code(), ex.getMessage())
                : Problems.of(status, ex.code(), ex.getMessage(),
                        ex.issues().stream().map(i -> new Problems.FieldError(i.field(), i.message())).toList());
        if (ex.currentStatus() != null) {
            problem.setProperty("currentStatus", ex.currentStatus());
        }
        return ResponseEntity.status(status).body(problem);
    }
}
