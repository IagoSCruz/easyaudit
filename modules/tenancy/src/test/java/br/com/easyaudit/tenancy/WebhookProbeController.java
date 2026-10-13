package br.com.easyaudit.tenancy;

import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import br.com.easyaudit.platform.tenant.TenantContext;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** Endpoint de teste que mostra o tenant do contexto e o corpo que chegou ao controller. */
@RestController
class WebhookProbeController {

    static final AtomicInteger CALLS = new AtomicInteger();

    @PostMapping("/webhooks/v1/probe")
    Map<String, String> probe(@RequestBody String body) {
        CALLS.incrementAndGet();
        return Map.of("tenant", TenantContext.require().toString(), "body", body);
    }
}
