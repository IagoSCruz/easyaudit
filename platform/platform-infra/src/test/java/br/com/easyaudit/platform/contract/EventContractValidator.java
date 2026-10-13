package br.com.easyaudit.platform.contract;

import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.json.JsonMapper;
import com.networknt.schema.Error;
import com.networknt.schema.InputFormat;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaLocation;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import org.yaml.snakeyaml.Yaml;

/**
 * Valida o JSON de um evento (envelope ou payload) contra os schemas do módulo {@code contracts}.
 * Os YAMLs de {@code contracts/schemas} são carregados do classpath e registrados sob uma base fictícia,
 * para que os {@code $ref} relativos entre arquivos funcionem.
 */
public final class EventContractValidator {

    private static final String BASE = "https://easyaudit.local/contracts/schemas/";
    private static final List<String> FILES = List.of("common", "cte", "nfe", "document", "events");

    private final SchemaRegistry registry;

    public EventContractValidator() {
        JsonMapper json = new JsonMapper();
        Map<String, String> schemas = new HashMap<>();
        for (String name : FILES) {
            try (InputStream in = getClass().getResourceAsStream("/contracts/schemas/" + name + ".yaml")) {
                if (in == null) {
                    throw new IllegalStateException("Schema não encontrado no classpath: " + name + ".yaml");
                }
                schemas.put(BASE + name + ".yaml", json.writeValueAsString(new Yaml().load(in)));
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        }
        this.registry = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12,
                builder -> builder.schemas(schemas));
    }

    /** Valida o envelope completo de um evento. */
    public List<Error> validateEnvelope(String json) {
        return validate("EventEnvelope", json);
    }

    /** Valida o payload ({@code data}) de um evento, por exemplo {@code AuditCompletedData}. */
    public List<Error> validatePayload(String payloadSchema, String json) {
        return validate(payloadSchema, json);
    }

    public void assertValidPayload(String payloadSchema, String json) {
        List<Error> errors = validatePayload(payloadSchema, json);
        if (!errors.isEmpty()) {
            throw new AssertionError("Payload inválido para " + payloadSchema + ": " + errors);
        }
    }

    private List<Error> validate(String schemaName, String json) {
        Schema schema = registry.getSchema(SchemaLocation.of(BASE + "events.yaml#/components/schemas/" + schemaName));
        schema.initializeValidators();
        return schema.validate(json, InputFormat.JSON,
                ctx -> ctx.executionConfig(cfg -> cfg.formatAssertionsEnabled(true)));
    }
}
