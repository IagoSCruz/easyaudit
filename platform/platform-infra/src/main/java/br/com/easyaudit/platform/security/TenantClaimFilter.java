package br.com.easyaudit.platform.security;

import java.io.IOException;

import br.com.easyaudit.platform.tenant.TenantContext;
import br.com.easyaudit.platform.web.Problems;
import br.com.easyaudit.shared.TenantId;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Em {@code /api/**}, exige a claim {@code tenant_id} (UUID) do token e executa o restante da requisição dentro do
 * {@link TenantContext}. Sem a claim, responde 403.
 */
public class TenantClaimFilter extends OncePerRequestFilter {

    public static final String CLAIM = "tenant_id";

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/api/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (!(authentication instanceof JwtAuthenticationToken jwtAuth)) {
            chain.doFilter(request, response);
            return;
        }
        TenantId tenant = parse(jwtAuth.getToken().getClaimAsString(CLAIM));
        if (tenant == null) {
            Problems.write(response, Problems.of(HttpStatus.FORBIDDEN, "TENANT_CLAIM_MISSING",
                    "O token não traz a claim tenant_id."));
            return;
        }
        MDC.put(TenantContext.MDC_KEY, tenant.toString());
        try {
            TenantContext.where(tenant).call(() -> {
                chain.doFilter(request, response);
                return null;
            });
        } catch (ServletException | IOException | RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new ServletException(e);
        } finally {
            MDC.remove(TenantContext.MDC_KEY);
        }
    }

    private static TenantId parse(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return TenantId.of(value);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
