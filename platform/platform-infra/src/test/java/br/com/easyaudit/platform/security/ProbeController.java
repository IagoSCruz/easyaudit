package br.com.easyaudit.platform.security;

import br.com.easyaudit.platform.tenant.TenantContext;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
class ProbeController {

    record Body(@NotBlank String name) {
    }

    @GetMapping("/api/v1/probe")
    String tenant() {
        return TenantContext.require().toString();
    }

    @PostMapping("/api/v1/probe")
    String create(@Valid @RequestBody Body body) {
        return body.name();
    }

    @GetMapping("/api/v1/boom")
    String boom() {
        throw new IllegalStateException("segredo interno");
    }

    @GetMapping("/admin/v1/probe")
    String admin() {
        return "admin";
    }
}
