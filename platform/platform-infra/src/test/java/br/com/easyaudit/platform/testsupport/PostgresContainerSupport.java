package br.com.easyaudit.platform.testsupport;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.concurrent.atomic.AtomicInteger;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.MountableFile;

/**
 * PostgreSQL 17 compartilhado entre os testes de integração da JVM (um único container), com as roles do ambiente
 * local. Cada contexto de teste recebe um banco novo e vazio, então os testes não se enxergam.
 * Uso: {@code @DynamicPropertySource static void db(DynamicPropertyRegistry r) { url = PostgresContainerSupport.register(r); }}
 */
public final class PostgresContainerSupport {

    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17")
            .withDatabaseName("easyaudit")
            .withUsername("postgres")
            .withPassword("postgres")
            .withCopyFileToContainer(MountableFile.forHostPath(initScript()), "/docker-entrypoint-initdb.d/00-init.sql");

    private static final AtomicInteger COUNTER = new AtomicInteger();

    static {
        POSTGRES.start();
    }

    private PostgresContainerSupport() {
    }

    /** Cria um banco novo e vazio (dono: {@code easyaudit_owner}) e devolve a JDBC URL dele. */
    public static String newDatabase() {
        String name = "it_" + COUNTER.incrementAndGet() + "_" + Long.toHexString(System.nanoTime());
        try (var c = DriverManager.getConnection(POSTGRES.getJdbcUrl(), "postgres", "postgres");
                var st = c.createStatement()) {
            st.execute("CREATE DATABASE " + name + " OWNER easyaudit_owner");
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
        return "jdbc:postgresql://" + POSTGRES.getHost() + ":" + POSTGRES.getMappedPort(5432) + "/" + name;
    }

    /** Banco novo acessado como superusuário. Devolve a JDBC URL. */
    public static String register(DynamicPropertyRegistry registry) {
        String url = newDatabase();
        registry.add("spring.datasource.url", () -> url);
        registry.add("spring.datasource.username", () -> "postgres");
        registry.add("spring.datasource.password", () -> "postgres");
        return url;
    }

    /**
     * Banco novo acessado como a aplicação real: conecta como {@code easyaudit_app} e roda o Flyway como
     * {@code easyaudit_owner}. Devolve a JDBC URL.
     */
    public static String registerApplicationRoles(DynamicPropertyRegistry registry) {
        String url = newDatabase();
        registry.add("spring.datasource.url", () -> url);
        registry.add("spring.datasource.username", () -> "easyaudit_app");
        registry.add("spring.datasource.password", () -> "easyaudit_app");
        registry.add("spring.flyway.url", () -> url);
        registry.add("spring.flyway.user", () -> "easyaudit_owner");
        registry.add("spring.flyway.password", () -> "easyaudit_owner");
        return url;
    }

    public static PostgreSQLContainer container() {
        return POSTGRES;
    }

    /** O mesmo script de roles do ambiente local ({@code easyaudit_owner} e {@code easyaudit_app}). */
    private static Path initScript() {
        Path dir = Path.of("").toAbsolutePath();
        while (dir != null) {
            Path candidate = dir.resolve("deploy/local/postgres/init.sql");
            if (Files.exists(candidate)) {
                return candidate;
            }
            dir = dir.getParent();
        }
        throw new IllegalStateException(
                "deploy/local/postgres/init.sql não encontrado a partir de " + Path.of("").toAbsolutePath());
    }
}
