package br.com.easyaudit.tenancy;

import br.com.easyaudit.platform.testsupport.TwoTenants;
import org.springframework.jdbc.core.JdbcTemplate;

/** Cria no banco os dois tenants de {@link TwoTenants}, com ids fixos, para testes de isolamento. */
public final class TenancyFixtures {

    private TenancyFixtures() {
    }

    public static void seedTwoTenants(JdbcTemplate jdbc) {
        jdbc.update("delete from tenancy.integration_credential");
        jdbc.update("delete from tenancy.tenant_cnpj");
        jdbc.update("delete from tenancy.cnpj_root");
        jdbc.update("delete from tenancy.tenant");
        insert(jdbc, TwoTenants.A.toString(), "Loja A", "loja-a", "11222333");
        insert(jdbc, TwoTenants.B.toString(), "Loja B", "loja-b", "99888777");
    }

    private static void insert(JdbcTemplate jdbc, String id, String name, String slug, String root) {
        jdbc.update("insert into tenancy.tenant (id, name, slug) values (?::uuid, ?, ?)", id, name, slug);
        jdbc.update("insert into tenancy.cnpj_root (root, tenant_id) values (?, ?::uuid)", root, id);
        jdbc.update("insert into tenancy.tenant_cnpj (id, tenant_id, cnpj, kind, root) values (gen_random_uuid(), ?::uuid, ?, 'ROOT', ?)",
                id, root, root);
    }
}
