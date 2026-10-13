package br.com.easyaudit.documents;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;

import br.com.easyaudit.documents.application.RawDocumentStorage;
import br.com.easyaudit.documents.domain.DocumentKind;
import br.com.easyaudit.documents.domain.Source;
import br.com.easyaudit.documents.infrastructure.persistence.DocumentRepository;
import br.com.easyaudit.platform.db.TenantRlsChecker;
import br.com.easyaudit.platform.tenant.TenantContext;
import br.com.easyaudit.platform.testsupport.PostgresContainerSupport;
import br.com.easyaudit.platform.testsupport.TwoTenants;
import br.com.easyaudit.shared.TenantId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest(classes = DocumentsTestApplication.class, properties = "spring.jpa.hibernate.ddl-auto=none")
class DocumentPersistenceIT {

    static String jdbcUrl;

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        jdbcUrl = PostgresContainerSupport.registerApplicationRoles(registry);
    }

    @Autowired
    DocumentRepository documents;
    @Autowired
    RawDocumentStorage storage;
    @Autowired
    TransactionTemplate tx;
    @Autowired
    JdbcTemplate app;

    private static final String KEY = "35261011222333000181570010000001231123456780";

    @BeforeEach
    void clean() {
        var owner = new JdbcTemplate(new DriverManagerDataSource(jdbcUrl, "easyaudit_owner", "easyaudit_owner"));
        owner.execute("truncate documents.raw_document, documents.pending_cancellation, documents.document cascade");
    }

    private <T> T as(TenantId tenant, java.util.function.Supplier<T> action) {
        try {
            return TenantContext.callAs(tenant, () -> tx.execute(status -> action.get()));
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void migrationsApplyAndAllTenantTablesHaveForcedRls() {
        var owner = new DriverManagerDataSource(jdbcUrl, "easyaudit_owner", "easyaudit_owner");
        assertThat(new TenantRlsChecker(owner).violations()).isEmpty();
        assertThat(app.queryForObject("""
                select count(*) from pg_class c join pg_namespace n on n.oid = c.relnamespace
                where n.nspname = 'documents' and c.relname in ('document', 'raw_document', 'pending_cancellation')
                  and c.relforcerowsecurity""", Integer.class)).isEqualTo(3);
    }

    @Test
    void insertIsIdempotentPerTenantKindAndKey() {
        UUID first = as(TwoTenants.A, () -> documents.insert(DocumentKind.CTE, KEY, Source.UPLOAD, null, "a".repeat(64)).orElseThrow());
        assertThat(as(TwoTenants.A, () -> documents.insert(DocumentKind.CTE, KEY, Source.WEBHOOK, null, "b".repeat(64)))).isEmpty();
        assertThat(as(TwoTenants.A, () -> documents.findIdByKey(DocumentKind.CTE, KEY))).contains(first);
        // a mesma chave em outro tenant é um registro próprio
        UUID other = as(TwoTenants.B, () -> documents.insert(DocumentKind.CTE, KEY, Source.UPLOAD, null, "a".repeat(64)).orElseThrow());
        assertThat(other).isNotEqualTo(first);
    }

    @Test
    void rlsHidesOtherTenantsRows() {
        UUID id = as(TwoTenants.A, () -> documents.insert(DocumentKind.CTE, KEY, Source.UPLOAD, null, "a".repeat(64)).orElseThrow());
        as(TwoTenants.A, () -> { storage.store(id, "<xml/>".getBytes()); return null; });

        assertThat(as(TwoTenants.A, () -> documents.findById(id))).isPresent();
        assertThat(as(TwoTenants.B, () -> documents.findById(id))).isEmpty();
        assertThat(as(TwoTenants.B, () -> storage.load(id))).isEmpty();
        // sem tenant no contexto nenhuma linha é visível
        Integer visible = tx.execute(s -> app.queryForObject("select count(*) from documents.document", Integer.class));
        assertThat(visible).isZero();
    }

    @Test
    void rlsBlocksWritingForAnotherTenant() {
        // sem o tenant no contexto o INSERT é barrado pela política
        assertThatThrownBy(() -> tx.executeWithoutResult(s -> app.update("""
                insert into documents.document (id, tenant_id, kind, access_key, source, raw_sha256)
                values (gen_random_uuid(), ?::uuid, 'CTE', ?, 'UPLOAD', ?)""", TwoTenants.A.toString(), KEY, "a".repeat(64))))
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    void rawDocumentRoundTripKeepsEveryByte() {
        byte[] bytes = new byte[] {0, 1, 2, (byte) 0xFF, (byte) 0xC3, (byte) 0xA9, 10, 13, 0};
        UUID id = as(TwoTenants.A, () -> documents.insert(DocumentKind.NFE, KEY, Source.UPLOAD, null, "a".repeat(64)).orElseThrow());
        as(TwoTenants.A, () -> { storage.store(id, bytes); return null; });
        assertThat(as(TwoTenants.A, () -> storage.load(id))).contains(bytes);
    }
}
