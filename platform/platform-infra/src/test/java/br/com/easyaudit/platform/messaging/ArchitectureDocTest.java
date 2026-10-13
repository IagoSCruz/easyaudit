package br.com.easyaudit.platform.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import br.com.easyaudit.contracts.events.EventType;
import org.junit.jupiter.api.Test;

/**
 * Garante que as routing keys e os exchanges citados em {@code docs/architecture.md} existem na topologia
 * declarada: o enum {@link EventType} (gerado do contrato) e as constantes de {@link MessagingTopology}.
 */
class ArchitectureDocTest {

    private static final Pattern CATALOG_ROW = Pattern.compile("^\\| `([^`]+)` \\|");
    private static final Pattern EXCHANGE = Pattern.compile("`(easyaudit\\.(?:events|dlx))`");

    private static String doc() throws IOException {
        Path dir = Path.of("").toAbsolutePath();
        while (dir != null) {
            Path candidate = dir.resolve("docs/architecture.md");
            if (Files.exists(candidate)) {
                return Files.readString(candidate);
            }
            dir = dir.getParent();
        }
        throw new IllegalStateException("docs/architecture.md não encontrado");
    }

    @Test
    void everyRoutingKeyInTheCatalogIsADeclaredEventType() throws IOException {
        List<String> cited = doc().lines().map(CATALOG_ROW::matcher).filter(Matcher::find)
                .map(m -> m.group(1)).filter(k -> k.matches("[a-z-]+\\.[a-z-]+")).toList();
        Set<String> declared = Arrays.stream(EventType.values()).map(EventType::getValue).collect(Collectors.toSet());

        assertThat(cited).isNotEmpty();
        assertThat(declared).containsAll(cited);
        // e o catálogo documenta todos os eventos declarados
        assertThat(cited).containsExactlyInAnyOrderElementsOf(declared);
    }

    @Test
    void citedExchangesExistInTheTopology() throws IOException {
        Matcher m = EXCHANGE.matcher(doc());
        Set<String> cited = new java.util.HashSet<>();
        while (m.find()) {
            cited.add(m.group(1));
        }
        assertThat(cited).containsExactlyInAnyOrder(MessagingTopology.EVENTS_EXCHANGE,
                MessagingTopology.DEAD_LETTER_EXCHANGE);
    }
}
