package br.com.easyaudit.platform.testsupport;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.MountableFile;

/**
 * Keycloak 26 com o realm easyaudit importado, compartilhado entre os testes da JVM.
 * Permite obter tokens JWT reais e atualizar atributos de tenant dos usuários de teste.
 */
public final class KeycloakContainerSupport {

    private static final GenericContainer<?> KEYCLOAK = new GenericContainer<>("quay.io/keycloak/keycloak:26.8.1")
            .withCommand("start-dev", "--import-realm")
            .withEnv("KC_BOOTSTRAP_ADMIN_USERNAME", "admin")
            .withEnv("KC_BOOTSTRAP_ADMIN_PASSWORD", "admin")
            .withEnv("KC_HEALTH_ENABLED", "true")
            .withExposedPorts(8080)
            .withCopyFileToContainer(MountableFile.forHostPath(realmFile()), "/opt/keycloak/data/import/easyaudit-realm.json")
            .waitingFor(Wait.forHttp("/realms/easyaudit/.well-known/openid-configuration")
                    .forPort(8080)
                    .forStatusCode(200)
                    .withStartupTimeout(Duration.ofMinutes(3)));

    private static final HttpClient HTTP = HttpClient.newHttpClient();
    private static final ObjectMapper MAPPER = new ObjectMapper();

    static {
        KEYCLOAK.start();
    }

    private KeycloakContainerSupport() {
    }

    public static GenericContainer<?> container() {
        return KEYCLOAK;
    }

    public static String baseUrl() {
        return "http://" + KEYCLOAK.getHost() + ":" + KEYCLOAK.getMappedPort(8080);
    }

    public static String issuerUri() {
        return baseUrl() + "/realms/easyaudit";
    }

    public static void register(DynamicPropertyRegistry registry) {
        registry.add("spring.security.oauth2.resourceserver.jwt.issuer-uri", KeycloakContainerSupport::issuerUri);
    }

    public static String bearer(String username, String password) {
        return "Bearer " + token(username, password);
    }

    public static String token(String username, String password) {
        try {
            String url = issuerUri() + "/protocol/openid-connect/token";
            String form = "grant_type=password&client_id=easyaudit-api&username="
                    + URLEncoder.encode(username, StandardCharsets.UTF_8)
                    + "&password=" + URLEncoder.encode(password, StandardCharsets.UTF_8);
            HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString(form))
                    .build();
            HttpResponse<String> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                throw new IllegalStateException("Falha ao obter token do Keycloak (" + resp.statusCode() + "): " + resp.body());
            }
            Map<String, Object> map = MAPPER.readValue(resp.body(), new TypeReference<>() {});
            return (String) map.get("access_token");
        } catch (IOException | InterruptedException e) {
            throw new RuntimeException(e);
        }
    }

    public static void setUserTenantId(String username, String tenantId) {
        try {
            String adminTokenUrl = baseUrl() + "/realms/master/protocol/openid-connect/token";
            String form = "grant_type=password&client_id=admin-cli&username=admin&password=admin";
            HttpRequest req = HttpRequest.newBuilder(URI.create(adminTokenUrl))
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString(form))
                    .build();
            HttpResponse<String> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                throw new IllegalStateException("Falha no login admin do Keycloak: " + resp.body());
            }
            Map<String, Object> tokenMap = MAPPER.readValue(resp.body(), new TypeReference<>() {});
            String adminToken = (String) tokenMap.get("access_token");

            HttpRequest findReq = HttpRequest.newBuilder(URI.create(
                            baseUrl() + "/admin/realms/easyaudit/users?username="
                                    + URLEncoder.encode(username, StandardCharsets.UTF_8)))
                    .header("Authorization", "Bearer " + adminToken)
                    .GET()
                    .build();
            HttpResponse<String> findResp = HTTP.send(findReq, HttpResponse.BodyHandlers.ofString());
            List<Map<String, Object>> users = MAPPER.readValue(findResp.body(), new TypeReference<>() {});
            if (users.isEmpty()) {
                throw new IllegalStateException("Usuário não encontrado: " + username);
            }
            Map<String, Object> user = users.get(0);
            String userId = (String) user.get("id");

            @SuppressWarnings("unchecked")
            Map<String, Object> attributes = (Map<String, Object>) user.getOrDefault("attributes", new java.util.HashMap<>());
            attributes.put("tenant_id", List.of(tenantId));
            user.put("attributes", attributes);

            HttpRequest updateReq = HttpRequest.newBuilder(URI.create(baseUrl() + "/admin/realms/easyaudit/users/" + userId))
                    .header("Authorization", "Bearer " + adminToken)
                    .header("Content-Type", "application/json")
                    .PUT(HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(user)))
                    .build();
            HttpResponse<String> updateResp = HTTP.send(updateReq, HttpResponse.BodyHandlers.ofString());
            if (updateResp.statusCode() >= 300) {
                throw new IllegalStateException("Falha ao atualizar usuário " + username + ": " + updateResp.body());
            }
        } catch (IOException | InterruptedException e) {
            throw new RuntimeException(e);
        }
    }

    private static Path realmFile() {
        Path dir = Path.of("").toAbsolutePath();
        while (dir != null) {
            Path candidate = dir.resolve("deploy/local/keycloak/easyaudit-realm.json");
            if (Files.exists(candidate)) {
                return candidate;
            }
            dir = dir.getParent();
        }
        throw new IllegalStateException("deploy/local/keycloak/easyaudit-realm.json não encontrado");
    }
}

