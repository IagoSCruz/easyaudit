package br.com.easyaudit.platform.contract;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import com.atlassian.oai.validator.mockmvc.OpenApiValidationMatchers;
import io.swagger.v3.core.util.Json;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.parser.OpenAPIV3Parser;
import io.swagger.v3.parser.core.models.ParseOptions;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.test.web.servlet.ResultMatcher;

/**
 * Matcher de MockMvc que valida a resposta real contra {@code contracts/openapi/easyaudit-api.yaml}.
 * Uso: {@code mockMvc.perform(get(...)).andExpect(OpenApiResponseValidator.conformsToContract())}.
 */
public final class OpenApiResponseValidator {

    private static final String SPEC = "openapi/easyaudit-api.yaml";

    private static final ResultMatcher MATCHER = OpenApiValidationMatchers.openApi().isValid(bundledSpec());

    private OpenApiResponseValidator() {
    }

    public static ResultMatcher conformsToContract() {
        return MATCHER;
    }

    /**
     * Copia as specs para um diretório temporário (o parser só resolve {@code $ref} relativos entre arquivos
     * no sistema de arquivos) e devolve a spec resolvida como um único documento.
     */
    private static String bundledSpec() {
        try {
            Path dir = Files.createTempDirectory("easyaudit-contracts");
            // copia a spec e todos os schemas compartilhados (contracts/schemas/*.yaml)
            List<String> files = new ArrayList<>(List.of("openapi/easyaudit-api.yaml"));
            for (Resource resource : new PathMatchingResourcePatternResolver()
                    .getResources("classpath*:contracts/schemas/*.yaml")) {
                files.add("schemas/" + resource.getFilename());
            }
            for (String file : files) {
                try (InputStream in = OpenApiResponseValidator.class.getResourceAsStream("/contracts/" + file)) {
                    if (in == null) {
                        throw new IllegalStateException("Arquivo não encontrado no classpath: contracts/" + file);
                    }
                    Path target = dir.resolve(file);
                    Files.createDirectories(target.getParent());
                    Files.copy(in, target);
                }
            }
            ParseOptions options = new ParseOptions();
            options.setResolve(true);
            OpenAPI api = new OpenAPIV3Parser().read(dir.resolve(SPEC).toString(), null, options);
            if (api == null) {
                throw new IllegalStateException("Não foi possível carregar a spec " + SPEC);
            }
            return Json.pretty(api);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
