package br.com.easyaudit.platform.tenant;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import br.com.easyaudit.platform.testsupport.PostgresContainerSupport;
import br.com.easyaudit.shared.TenantId;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest(classes = TenantTestApplication.class,
        properties = {"spring.jpa.hibernate.ddl-auto=create-drop"})
class TenantTransactionIT {

    private static final TenantId TENANT_A = TenantId.of("11111111-1111-4111-8111-111111111111");
    private static final TenantId TENANT_B = TenantId.of("22222222-2222-4222-8222-222222222222");

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        PostgresContainerSupport.register(registry);
    }

    @Autowired
    TransactionTemplate tx;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    EntityManager em;

    private String currentSetting() {
        return tx.execute(status -> jdbc.queryForObject("select current_setting('app.tenant_id', true)", String.class));
    }

    @Test
    void eachTransactionSeesItsOwnTenantSetting() {
        assertThat(inTenant(TENANT_A, this::currentSetting)).isEqualTo(TENANT_A.toString());
        assertThat(inTenant(TENANT_B, this::currentSetting)).isEqualTo(TENANT_B.toString());
    }

    @Test
    void settingDoesNotLeakToLaterTransactionsOnPooledConnections() {
        inTenant(TENANT_A, this::currentSetting);
        // sem tenant no contexto: a configuração local da transação anterior já foi descartada
        String after = currentSetting();
        assertThat(after == null || after.isEmpty()).isTrue();
    }

    @Test
    void concurrentTenantsDoNotInterfere() throws Exception {
        CountDownLatch bothInside = new CountDownLatch(2);
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<String> a = pool.submit(() -> inTenantHolding(TENANT_A, bothInside));
            Future<String> b = pool.submit(() -> inTenantHolding(TENANT_B, bothInside));
            assertThat(a.get()).isEqualTo(TENANT_A.toString());
            assertThat(b.get()).isEqualTo(TENANT_B.toString());
        }
    }

    @Test
    void tenantIdDiscriminatorFiltersEntities() {
        inTenant(TENANT_A, () -> tx.execute(s -> {
            em.persist(new Note("da A"));
            return null;
        }));
        inTenant(TENANT_B, () -> tx.execute(s -> {
            em.persist(new Note("da B"));
            return null;
        }));
        List<String> forA = inTenant(TENANT_A, () -> tx.execute(s ->
                em.createQuery("select n.text from Note n", String.class).getResultList()));
        assertThat(forA).containsExactly("da A");
        UUID stored = jdbc.queryForObject("select tenant_id from note where text = 'da B'", UUID.class);
        assertThat(stored).isEqualTo(TENANT_B.value());
    }

    private String inTenantHolding(TenantId tenant, CountDownLatch latch) throws Exception {
        return TenantContext.callAs(tenant, () -> tx.execute(status -> {
            latch.countDown();
            try {
                latch.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return jdbc.queryForObject("select current_setting('app.tenant_id', true)", String.class);
        }));
    }

    private <T> T inTenant(TenantId tenant, java.util.function.Supplier<T> action) {
        try {
            return TenantContext.callAs(tenant, action::get);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
