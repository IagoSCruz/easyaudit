package br.com.easyaudit.quotes.infrastructure.persistence;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import br.com.easyaudit.platform.tenant.TenantContext;
import br.com.easyaudit.quotes.domain.QuoteContent;
import br.com.easyaudit.quotes.domain.StoredQuote;
import br.com.easyaudit.quotes.domain.StoredQuote.LinkMethod;
import br.com.easyaudit.quotes.domain.StoredQuote.LinkStatus;
import br.com.easyaudit.quotes.domain.StoredQuote.Source;
import br.com.easyaudit.quotes.domain.StoredRevision;
import br.com.easyaudit.shared.UuidV7;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class QuoteRepository {

    private static final String QUOTE_COLUMNS = """
            id, source, external_id, carrier_cnpj, nfe_key, order_reference, link_status, link_method,
            linked_nfe_document_id, current_revision, created_at""";
    private static final String REVISION_COLUMNS = """
            revision, carrier_cnpj, service, amount, quoted_at, destination_cep, weight_kg, cargo_value, delivery_days,
            nfe_key, order_reference, content_hash, received_at""";

    private final JdbcClient jdbc;

    public QuoteRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Cria a cotação (revisão 1 ainda não gravada). @return o id novo, ou vazio se (origem, id externo) já existe */
    public Optional<UUID> insert(Source source, String externalId, QuoteContent content) {
        return jdbc.sql("""
                INSERT INTO quotes.quote (id, tenant_id, source, external_id, carrier_cnpj, nfe_key, order_reference)
                VALUES (:id, :tenant, :source, :external, :carrier, :nfeKey, :order)
                ON CONFLICT (tenant_id, source, external_id) DO NOTHING
                RETURNING id""")
                .param("id", UuidV7.next()).param("tenant", TenantContext.require().value())
                .param("source", source.name()).param("external", externalId).param("carrier", content.carrierCnpj())
                .param("nfeKey", content.nfeKey()).param("order", content.orderReference())
                .query(UUID.class).optional();
    }

    /** Cotação travada para atualização, para serializar reenvios concorrentes. */
    public Optional<StoredQuote> findForUpdate(Source source, String externalId) {
        return jdbc.sql("SELECT " + QUOTE_COLUMNS + " FROM quotes.quote WHERE source = :source AND external_id = :external FOR UPDATE")
                .param("source", source.name()).param("external", externalId).query((rs, n) -> map(rs)).optional();
    }

    public Optional<StoredQuote> findById(UUID id) {
        return jdbc.sql("SELECT " + QUOTE_COLUMNS + " FROM quotes.quote WHERE id = :id")
                .param("id", id).query((rs, n) -> map(rs)).optional();
    }

    public void insertRevision(UUID quoteId, int revision, QuoteContent c) {
        jdbc.sql("""
                INSERT INTO quotes.quote_revision
                    (id, tenant_id, quote_id, revision, carrier_cnpj, service, amount, quoted_at, destination_cep,
                     weight_kg, cargo_value, delivery_days, nfe_key, order_reference, content_hash)
                VALUES (:id, :tenant, :quote, :revision, :carrier, :service, :amount, :quotedAt, :cep, :weight, :cargo,
                        :days, :nfeKey, :order, :hash)""")
                .param("id", UuidV7.next()).param("tenant", TenantContext.require().value()).param("quote", quoteId)
                .param("revision", revision).param("carrier", c.carrierCnpj()).param("service", c.service())
                .param("amount", c.amount()).param("quotedAt", c.quotedAt()).param("cep", c.destinationCep())
                .param("weight", c.weightKg()).param("cargo", c.cargoValue()).param("days", c.deliveryDays())
                .param("nfeKey", c.nfeKey()).param("order", c.orderReference()).param("hash", c.hash()).update();
    }

    /** Aponta a cotação para a revisão nova e atualiza os campos desnormalizados usados no vínculo. */
    public void setCurrentRevision(UUID quoteId, int revision, QuoteContent c) {
        jdbc.sql("""
                UPDATE quotes.quote SET current_revision = :revision, carrier_cnpj = :carrier, nfe_key = :nfeKey,
                    order_reference = :order, updated_at = now() WHERE id = :id""")
                .param("id", quoteId).param("revision", revision).param("carrier", c.carrierCnpj())
                .param("nfeKey", c.nfeKey()).param("order", c.orderReference()).update();
    }

    public Optional<StoredRevision> currentRevision(UUID quoteId) {
        return jdbc.sql("SELECT " + REVISION_COLUMNS + " FROM quotes.quote_revision WHERE quote_id = :quote"
                        + " ORDER BY revision DESC LIMIT 1")
                .param("quote", quoteId).query((rs, n) -> mapRevision(rs)).optional();
    }

    public List<StoredRevision> revisions(UUID quoteId) {
        return jdbc.sql("SELECT " + REVISION_COLUMNS + " FROM quotes.quote_revision WHERE quote_id = :quote ORDER BY revision")
                .param("quote", quoteId).query((rs, n) -> mapRevision(rs)).list();
    }

    public void setLink(UUID quoteId, LinkStatus status, LinkMethod method, UUID nfeDocumentId) {
        jdbc.sql("""
                UPDATE quotes.quote SET link_status = :status, link_method = :method, linked_nfe_document_id = :doc,
                    updated_at = now() WHERE id = :id""")
                .param("id", quoteId).param("status", status.name()).param("method", method == null ? null : method.name())
                .param("doc", nfeDocumentId).update();
    }

    /** Cotações do tenant que esperam a NF-e com esta chave (ainda sem vínculo por chave). */
    public List<StoredQuote> findByNfeKey(String nfeKey) {
        return jdbc.sql("SELECT " + QUOTE_COLUMNS + " FROM quotes.quote WHERE nfe_key = :key ORDER BY created_at, id")
                .param("key", nfeKey).query((rs, n) -> map(rs)).list();
    }

    /** Cotações sem chave de NF-e que usam a referência de pedido. */
    public List<StoredQuote> findByOrderReference(String orderReference) {
        return jdbc.sql("SELECT " + QUOTE_COLUMNS + " FROM quotes.quote WHERE order_reference = :ref AND nfe_key IS NULL"
                        + " ORDER BY created_at, id")
                .param("ref", orderReference).query((rs, n) -> map(rs)).list();
    }

    /** Cotação aplicável: a revisão vigente mais recente, vinculada ao documento, de transportadora da mesma raiz. */
    public record Applicable(UUID quoteId, int revision, BigDecimal amount, OffsetDateTime quotedAt, String carrierCnpj) {
    }

    public Optional<Applicable> applicable(UUID nfeDocumentId, String carrierRoot) {
        return jdbc.sql("""
                SELECT q.id, r.revision, r.amount, r.quoted_at, r.carrier_cnpj
                FROM quotes.quote q JOIN quotes.quote_revision r ON r.quote_id = q.id AND r.revision = q.current_revision
                WHERE q.linked_nfe_document_id = :doc AND q.link_status = 'LINKED'
                  AND substring(q.carrier_cnpj from 1 for 8) = :root
                ORDER BY r.quoted_at DESC, q.created_at DESC, q.id DESC
                LIMIT 1""")
                .param("doc", nfeDocumentId).param("root", carrierRoot)
                .query((rs, n) -> new Applicable(rs.getObject(1, UUID.class), rs.getInt(2), rs.getBigDecimal(3),
                        rs.getObject(4, OffsetDateTime.class), rs.getString(5).trim())).optional();
    }

    /** Filtros da listagem; campo nulo = sem filtro. */
    public record Filter(Source source, String carrierCnpj, LinkStatus linkStatus, String orderReference, String nfeKey,
            OffsetDateTime from, OffsetDateTime to) {
    }

    public List<StoredQuote> search(Filter f, int limit, long offset) {
        var sql = new StringBuilder("SELECT " + QUOTE_COLUMNS + " FROM quotes.quote q WHERE 1 = 1");
        var params = new ArrayList<Object[]>();
        where(f, sql, params);
        sql.append(" ORDER BY created_at DESC, id DESC LIMIT :limit OFFSET :offset");
        var spec = jdbc.sql(sql.toString());
        for (Object[] p : params) {
            spec = spec.param((String) p[0], p[1]);
        }
        return spec.param("limit", limit).param("offset", offset).query((rs, n) -> map(rs)).list();
    }

    public long count(Filter f) {
        var sql = new StringBuilder("SELECT count(*) FROM quotes.quote q WHERE 1 = 1");
        var params = new ArrayList<Object[]>();
        where(f, sql, params);
        var spec = jdbc.sql(sql.toString());
        for (Object[] p : params) {
            spec = spec.param((String) p[0], p[1]);
        }
        return spec.query(Long.class).single();
    }

    private static void where(Filter f, StringBuilder sql, List<Object[]> params) {
        if (f.source() != null) {
            sql.append(" AND source = :source");
            params.add(new Object[] {"source", f.source().name()});
        }
        if (f.carrierCnpj() != null) {
            sql.append(" AND carrier_cnpj = :carrier");
            params.add(new Object[] {"carrier", f.carrierCnpj()});
        }
        if (f.linkStatus() != null) {
            sql.append(" AND link_status = :status");
            params.add(new Object[] {"status", f.linkStatus().name()});
        }
        if (f.orderReference() != null) {
            sql.append(" AND order_reference = :order");
            params.add(new Object[] {"order", f.orderReference()});
        }
        if (f.nfeKey() != null) {
            sql.append(" AND nfe_key = :nfeKey");
            params.add(new Object[] {"nfeKey", f.nfeKey()});
        }
        if (f.from() != null) {
            sql.append(" AND created_at >= :from");
            params.add(new Object[] {"from", f.from()});
        }
        if (f.to() != null) {
            sql.append(" AND created_at <= :to");
            params.add(new Object[] {"to", f.to()});
        }
    }

    private static StoredQuote map(java.sql.ResultSet rs) throws java.sql.SQLException {
        String method = rs.getString("link_method");
        String nfeKey = rs.getString("nfe_key");
        return new StoredQuote(rs.getObject("id", UUID.class), Source.valueOf(rs.getString("source")),
                rs.getString("external_id"), rs.getString("carrier_cnpj").trim(), nfeKey == null ? null : nfeKey.trim(),
                rs.getString("order_reference"), LinkStatus.valueOf(rs.getString("link_status")),
                method == null ? null : LinkMethod.valueOf(method), rs.getObject("linked_nfe_document_id", UUID.class),
                rs.getInt("current_revision"), rs.getObject("created_at", OffsetDateTime.class));
    }

    private static StoredRevision mapRevision(java.sql.ResultSet rs) throws java.sql.SQLException {
        String nfeKey = rs.getString("nfe_key");
        var content = new QuoteContent(rs.getString("carrier_cnpj").trim(), rs.getString("service"),
                rs.getBigDecimal("amount"), rs.getObject("quoted_at", OffsetDateTime.class),
                rs.getString("destination_cep").trim(), rs.getBigDecimal("weight_kg"), rs.getBigDecimal("cargo_value"),
                (Integer) rs.getObject("delivery_days"), nfeKey == null ? null : nfeKey.trim(),
                rs.getString("order_reference"));
        return new StoredRevision(rs.getInt("revision"), content, rs.getString("content_hash").trim(),
                rs.getObject("received_at", OffsetDateTime.class));
    }
}
