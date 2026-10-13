package br.com.easyaudit.tenancy;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import br.com.easyaudit.platform.testsupport.PostgresContainerSupport;
import br.com.easyaudit.shared.Cnpj;
import br.com.easyaudit.shared.TenantId;
import br.com.easyaudit.tenancy.api.TenantDirectory;
import br.com.easyaudit.tenancy.application.CredentialService;
import br.com.easyaudit.tenancy.application.TenantService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest(classes = TenancyTestApplication.class, properties = "spring.jpa.hibernate.ddl-auto=none")
class TenantDirectoryIT {

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        PostgresContainerSupport.registerApplicationRoles(registry);
    }

    @Autowired
    TenantDirectory directory;
    @Autowired
    TenantService tenants;
    @Autowired
    CredentialService credentials;
    @Autowired
    JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        jdbc.update("delete from tenancy.integration_credential");
        jdbc.update("delete from tenancy.tenant_cnpj");
        jdbc.update("delete from tenancy.cnpj_root");
        jdbc.update("delete from tenancy.tenant");
    }

    @Test
    void ownsCnpjConsidersRootsAndFullEntries() {
        TenantId a = new TenantId(tenants.create("A", "a", List.of("11222333", "12ABC34501DE35")).id());
        TenantId b = new TenantId(tenants.create("B", "b", List.of("99888777")).id());

        assertThat(directory.ownsCnpj(a, Cnpj.of("11222333000181"))).isTrue();
        assertThat(directory.ownsCnpj(a, Cnpj.of("12ABC34501DE35"))).isTrue();
        assertThat(directory.ownsCnpj(b, Cnpj.of("11222333000181"))).isFalse();
    }

    @Test
    void resolvesCredentialWithTheClearSecretOnlyInMemory() {
        TenantId a = new TenantId(tenants.create("A", "a", List.of("11222333")).id());
        var created = credentials.create(a.value(), "Webhook");

        var resolved = directory.resolveCredential(created.credential().keyId()).orElseThrow();
        assertThat(resolved.tenant()).isEqualTo(a);
        assertThat(resolved.secret()).isEqualTo(created.secret());
        assertThat(resolved.active()).isTrue();
        assertThat(resolved.toString()).doesNotContain(created.secret());

        credentials.revoke(a.value(), created.credential().keyId());
        assertThat(directory.resolveCredential(created.credential().keyId()).orElseThrow().active()).isFalse();
        assertThat(directory.resolveCredential("ea_inexistente")).isEmpty();
    }
}
