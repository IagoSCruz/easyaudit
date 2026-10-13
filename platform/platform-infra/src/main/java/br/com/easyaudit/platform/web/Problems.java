package br.com.easyaudit.platform.web;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import tools.jackson.databind.json.JsonMapper;

/** Fábrica de erros RFC 9457 com as extensões {@code code} e {@code errors[] {field, message}} do contrato. */
public final class Problems {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private Problems() {
    }

    public record FieldError(String field, String message) {
    }

    public static ProblemDetail of(HttpStatus status, String code, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setTitle(status.getReasonPhrase());
        problem.setProperty("code", code);
        return problem;
    }

    public static ProblemDetail of(HttpStatus status, String code, String detail, List<FieldError> errors) {
        ProblemDetail problem = of(status, code, detail);
        problem.setProperty("errors", errors);
        return problem;
    }

    /** Escreve o problema na resposta (usado por filtros e handlers do Spring Security, fora do MVC). */
    public static void write(HttpServletResponse response, ProblemDetail problem) throws IOException {
        response.setStatus(problem.getStatus());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("type", problem.getType() == null ? "about:blank" : problem.getType().toString());
        body.put("title", problem.getTitle());
        body.put("status", problem.getStatus());
        if (problem.getDetail() != null) {
            body.put("detail", problem.getDetail());
        }
        if (problem.getProperties() != null) {
            body.putAll(problem.getProperties());
        }
        MAPPER.writeValue(response.getWriter(), body);
    }
}
