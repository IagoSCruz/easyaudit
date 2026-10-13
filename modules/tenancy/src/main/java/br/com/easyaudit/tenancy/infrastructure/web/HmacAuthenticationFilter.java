package br.com.easyaudit.tenancy.infrastructure.web;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import br.com.easyaudit.platform.tenant.TenantContext;
import br.com.easyaudit.platform.web.Problems;
import br.com.easyaudit.tenancy.api.TenantDirectory;
import br.com.easyaudit.tenancy.api.TenantDirectory.WebhookCredential;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Autentica {@code /webhooks/**} por HMAC: {@code X-EasyAudit-Key-Id}, {@code X-EasyAudit-Timestamp} (epoch em
 * segundos, janela de ±300 s) e {@code X-EasyAudit-Signature} ({@code sha256=} + HMAC-SHA256 hexadecimal de
 * {@code "<timestamp>.<corpo bruto>"}). O tenant é o da credencial. O corpo bruto é lido uma única vez (limite
 * configurável; acima dele, 413) e reposto na requisição.
 */
public class HmacAuthenticationFilter extends OncePerRequestFilter {

    public static final String KEY_ID_HEADER = "X-EasyAudit-Key-Id";
    public static final String TIMESTAMP_HEADER = "X-EasyAudit-Timestamp";
    public static final String SIGNATURE_HEADER = "X-EasyAudit-Signature";
    public static final long WINDOW_SECONDS = 300;

    private static final Logger log = LoggerFactory.getLogger(HmacAuthenticationFilter.class);

    private final TenantDirectory directory;
    private final long maxBodyBytes;
    private final Clock clock;

    public HmacAuthenticationFilter(TenantDirectory directory, long maxBodyBytes, Clock clock) {
        this.directory = directory;
        this.maxBodyBytes = maxBodyBytes;
        this.clock = clock;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (request.getContentLengthLong() > maxBodyBytes) {
            tooLarge(response);
            return;
        }
        byte[] body = request.getInputStream().readNBytes((int) maxBodyBytes + 1);
        if (body.length > maxBodyBytes) {
            tooLarge(response);
            return;
        }

        Optional<WebhookCredential> credential = authenticate(request, body);
        if (credential.isEmpty()) {
            Problems.write(response, Problems.of(HttpStatus.UNAUTHORIZED, "INVALID_SIGNATURE",
                    "Credencial, timestamp ou assinatura inválidos."));
            return;
        }
        WebhookCredential cred = credential.get();
        SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
                cred.keyId(), null, List.of(new SimpleGrantedAuthority("ROLE_WEBHOOK"))));
        MDC.put(TenantContext.MDC_KEY, cred.tenant().toString());
        try {
            TenantContext.where(cred.tenant()).call(() -> {
                chain.doFilter(new CachedBodyRequest(request, body), response);
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

    private Optional<WebhookCredential> authenticate(HttpServletRequest request, byte[] body) {
        String keyId = request.getHeader(KEY_ID_HEADER);
        String timestamp = request.getHeader(TIMESTAMP_HEADER);
        String signature = request.getHeader(SIGNATURE_HEADER);
        if (keyId == null || timestamp == null || signature == null || !signature.startsWith("sha256=")) {
            return Optional.empty();
        }
        long epoch;
        try {
            epoch = Long.parseLong(timestamp.trim());
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
        if (Math.abs(clock.instant().getEpochSecond() - epoch) > WINDOW_SECONDS) {
            return Optional.empty();
        }
        Optional<WebhookCredential> credential = directory.resolveCredential(keyId).filter(WebhookCredential::active);
        if (credential.isEmpty()) {
            return Optional.empty();
        }
        String expected = "sha256=" + sign(credential.get().secret(), timestamp.trim(), body);
        // comparação em tempo constante
        boolean valid = MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),
                signature.trim().toLowerCase().getBytes(StandardCharsets.UTF_8));
        if (!valid) {
            log.warn("Assinatura de webhook inválida para keyId={}", keyId);
        }
        return valid ? credential : Optional.empty();
    }

    /** HMAC-SHA256 hexadecimal de {@code "<timestamp>.<corpo>"}. */
    public static String sign(String secret, String timestamp, byte[] body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            mac.update((timestamp + ".").getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(mac.doFinal(body));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void tooLarge(HttpServletResponse response) throws IOException {
        Problems.write(response, Problems.of(HttpStatus.CONTENT_TOO_LARGE, "PAYLOAD_TOO_LARGE",
                "O corpo da requisição excede o limite permitido."));
    }
}
