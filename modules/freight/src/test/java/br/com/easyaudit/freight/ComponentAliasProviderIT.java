package br.com.easyaudit.freight;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import br.com.easyaudit.freight.api.ComponentAliasProvider;
import br.com.easyaudit.platform.tenant.TenantContext;
import br.com.easyaudit.platform.testsupport.PostgresContainerSupport;
import br.com.easyaudit.platform.testsupport.TwoTenants;
import br.com.easyaudit.freight.application.CarrierService;
import br.com.easyaudit.freight.application.ComponentAliasService;
import br.com.easyaudit.shared.TenantId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest(classes = FreightTestApplication.class,
        properties = {"spring.jpa.hibernate.ddl-auto=none", "easyaudit.messaging.listeners.enabled=false"})
class ComponentAliasProviderIT {

    static String jdbcUrl;

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        jdbcUrl = PostgresContainerSupport.registerApplicationRoles(registry);
    }

    @Autowired
    ComponentAliasProvider provider;
    @Autowired
    CarrierService carriers;
    @Autowired
    ComponentAliasService aliases;

    private <T> T as(TenantId tenant, java.util.concurrent.Callable<T> call) throws Exception {
        return TenantContext.callAs(tenant, call);
    }

    @BeforeEach
    void clean() {
        new JdbcTemplate(new DriverManagerDataSource(jdbcUrl, "postgres", "postgres"))
                .execute("truncate freight.component_alias, freight.carrier cascade");
    }

    @Test
    void returnsOnlyTheAliasesOfTheRequestedCarrierAndTenant() throws Exception {
        var carrierX = as(TwoTenants.A, () -> carriers.create("11222333000181", "X"));
        var carrierY = as(TwoTenants.A, () -> carriers.create("45723174000110", "Y"));
        var carrierXOfB = as(TwoTenants.B, () -> carriers.create("11222333000181", "X do B"));
        as(TwoTenants.A, () -> aliases.create(carrierX.id(), "TX EMERGENCIAL", "TDE"));
        as(TwoTenants.A, () -> aliases.create(carrierX.id(), "Seg. Carga", "AD_VALOREM"));
        as(TwoTenants.A, () -> aliases.create(carrierY.id(), "TX EMERGENCIAL", "OTHER"));
        as(TwoTenants.B, () -> aliases.create(carrierXOfB.id(), "TAXA DO B", "GRIS"));

        Map<String, String> forX = as(TwoTenants.A, () -> provider.aliasesByCarrierCnpj("11222333000181"));
        assertThat(forX).containsExactlyInAnyOrderEntriesOf(Map.of("TX EMERGENCIAL", "TDE", "SEG CARGA", "AD_VALOREM"));

        assertThat(as(TwoTenants.A, () -> provider.aliasesByCarrierCnpj("45723174000110")))
                .containsExactlyEntriesOf(Map.of("TX EMERGENCIAL", "OTHER"));
        assertThat(as(TwoTenants.B, () -> provider.aliasesByCarrierCnpj("11222333000181")))
                .containsExactlyEntriesOf(Map.of("TAXA DO B", "GRIS"));
        assertThat(as(TwoTenants.A, () -> provider.aliasesByCarrierCnpj("99888777000191"))).isEmpty();
    }
}
