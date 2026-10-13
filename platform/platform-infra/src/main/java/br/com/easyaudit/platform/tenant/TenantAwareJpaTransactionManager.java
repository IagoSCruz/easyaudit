package br.com.easyaudit.platform.tenant;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;

import jakarta.persistence.EntityManagerFactory;

import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.orm.jpa.EntityManagerHolder;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Gerenciador de transações que, no início de cada transação com tenant no contexto, executa
 * {@code set_config('app.tenant_id', ?, true)}. Com {@code is_local = true} o valor vale só até o fim da
 * transação, então conexões do pool nunca vazam o tenant. As políticas de Row Level Security leem esse valor.
 */
public class TenantAwareJpaTransactionManager extends JpaTransactionManager {

    public static final String SETTING = "app.tenant_id";

    public TenantAwareJpaTransactionManager(EntityManagerFactory emf) {
        super(emf);
    }

    @Override
    protected void doBegin(Object transaction, TransactionDefinition definition) {
        super.doBegin(transaction, definition);
        TenantContext.current().ifPresent(tenant -> applyTenant(tenant.toString()));
    }

    private void applyTenant(String tenantId) {
        EntityManagerHolder holder = (EntityManagerHolder) TransactionSynchronizationManager
                .getResource(obtainEntityManagerFactory());
        try {
            var handle = getJpaDialect().getJdbcConnection(holder.getEntityManager(), false);
            if (handle == null) {
                throw new CannotGetJdbcConnectionException("Não foi possível obter a conexão JDBC da transação");
            }
            Connection connection = handle.getConnection();
            try (PreparedStatement ps = connection.prepareStatement("select set_config('" + SETTING + "', ?, true)")) {
                ps.setString(1, tenantId);
                ps.execute();
            }
        } catch (SQLException e) {
            throw new CannotGetJdbcConnectionException("Falha ao aplicar o tenant na transação", e);
        }
    }
}
