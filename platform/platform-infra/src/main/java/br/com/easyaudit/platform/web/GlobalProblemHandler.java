package br.com.easyaudit.platform.web;

import java.util.List;

import org.slf4j.Logger;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/** Handler global: todo erro sai como {@code application/problem+json} com {@code code} estável. */
@RestControllerAdvice
@Order(Ordered.LOWEST_PRECEDENCE)
public class GlobalProblemHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalProblemHandler.class);

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException ex,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        List<Problems.FieldError> errors = ex.getBindingResult().getFieldErrors().stream()
                .map(e -> new Problems.FieldError(e.getField(), String.valueOf(e.getDefaultMessage())))
                .toList();
        ProblemDetail problem = Problems.of(HttpStatus.UNPROCESSABLE_ENTITY, "VALIDATION_ERROR",
                "Há campos inválidos na requisição.", errors);
        return handleExceptionInternal(ex, problem, headers, HttpStatus.UNPROCESSABLE_ENTITY, request);
    }

    @Override
    protected ResponseEntity<Object> handleExceptionInternal(Exception ex, Object body, HttpHeaders headers,
            HttpStatusCode statusCode, WebRequest request) {
        if (body instanceof ProblemDetail problem && !problem.getProperties().containsKey("code")) {
            problem.setProperty("code", HttpStatus.valueOf(statusCode.value()).name());
        }
        return super.handleExceptionInternal(ex, body, headers, statusCode, request);
    }

    @ExceptionHandler(PayloadTooLargeException.class)
    ResponseEntity<ProblemDetail> payloadTooLarge(PayloadTooLargeException ex) {
        return ResponseEntity.status(HttpStatus.CONTENT_TOO_LARGE)
                .body(Problems.of(HttpStatus.CONTENT_TOO_LARGE, "PAYLOAD_TOO_LARGE", ex.getMessage()));
    }

    @ExceptionHandler(AccessDeniedException.class)
    ResponseEntity<ProblemDetail> accessDenied(AccessDeniedException ex) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(Problems.of(HttpStatus.FORBIDDEN, "FORBIDDEN", "Acesso negado."));
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ProblemDetail> unexpected(Exception ex) {
        log.error("Erro inesperado", ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Problems.of(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "Erro interno."));
    }
}
