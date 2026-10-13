package br.com.easyaudit.platform.db;

import java.util.List;
import java.util.Set;

import javax.sql.DataSource;

import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Teste de arquitetura de banco: toda tabela com a coluna {@code tenant_id} precisa ter RLS ligada e forçada
 * e a política padrão {@code tenant_isolation} (criada por {@code platform.enable_tenant_rls}).
 * Chame {@link #violations()} em um teste de integração contra o banco já migrado.
 */
public class TenantRlsChecker {

    private static final String SQL = """
            SELECT n.nspname || '.' || c.relname AS table_name
            FROM pg_class c
            JOIN pg_namespace n ON n.oid = c.relnamespace
            JOIN pg_attribute a ON a.attrelid = c.oid AND a.attname = 'tenant_id' AND NOT a.attisdropped
            WHERE c.relkind IN ('r', 'p')
              AND n.nspname NOT IN ('pg_catalog', 'information_schema')
              AND NOT (c.relrowsecurity AND c.relforcerowsecurity
                       AND EXISTS (SELECT 1 FROM pg_policy p
                                   WHERE p.polrelid = c.oid AND p.polname = 'tenant_isolation'
                                     AND pg_get_expr(p.polqual, p.polrelid) LIKE '%app.tenant_id%'
                                     AND pg_get_expr(p.polwithcheck, p.polrelid) LIKE '%app.tenant_id%'))
            ORDER BY 1
            """;

    /**
     * Tabelas do módulo {@code tenancy} que guardam {@code tenant_id} mas ficam fora da RLS por decisão de projeto:
     * são consultadas antes de existir um tenant no contexto (administração da plataforma, lookup por {@code keyId}).
     */
    public static final Set<String> TENANCY_EXEMPT =
            Set.of("tenancy.tenant_cnpj", "tenancy.cnpj_root", "tenancy.integration_credential");

    private final JdbcTemplate jdbc;
    private final Set<String> exempt;

    public TenantRlsChecker(DataSource dataSource) {
        this(dataSource, TENANCY_EXEMPT);
    }

    public TenantRlsChecker(DataSource dataSource, Set<String> exempt) {
        this.jdbc = new JdbcTemplate(dataSource);
        this.exempt = exempt;
    }

    /** Nomes das tabelas com {@code tenant_id} sem RLS forçada e política padrão. */
    public List<String> violations() {
        return jdbc.queryForList(SQL, String.class).stream().filter(t -> !exempt.contains(t)).toList();
    }
}
