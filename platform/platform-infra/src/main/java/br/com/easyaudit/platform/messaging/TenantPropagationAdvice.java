package br.com.easyaudit.platform.messaging;

import br.com.easyaudit.platform.tenant.TenantContext;
import br.com.easyaudit.shared.TenantId;
import org.aopalliance.intercept.MethodInterceptor;
import org.aopalliance.intercept.MethodInvocation;
import org.slf4j.MDC;
import org.springframework.amqp.core.Message;

/**
 * Wrapper dos listeners: lê o header {@code x-tenant-id} e executa o tratamento dentro do escopo
 * {@link TenantContext}. Mensagens sem o header rodam sem tenant (eventos de plataforma).
 */
public class TenantPropagationAdvice implements MethodInterceptor {

    public static final String EVENT_ID_MDC = "eventId";

    @Override
    public Object invoke(MethodInvocation invocation) throws Throwable {
        Message message = findMessage(invocation.getArguments());
        Object header = message == null ? null : message.getMessageProperties().getHeader(MessagingHeaders.TENANT_ID);
        String eventId = message == null ? null : message.getMessageProperties().getMessageId();
        // campos do log estruturado durante o tratamento da mensagem
        if (eventId != null) {
            MDC.put(EVENT_ID_MDC, eventId);
        }
        try {
            if (header == null) {
                return invocation.proceed();
            }
            TenantId tenant = TenantId.of(header.toString());
            MDC.put(TenantContext.MDC_KEY, tenant.toString());
            return TenantContext.where(tenant).call(invocation::proceed);
        } finally {
            MDC.remove(EVENT_ID_MDC);
            MDC.remove(TenantContext.MDC_KEY);
        }
    }

    private static Message findMessage(Object[] args) {
        for (Object arg : args) {
            if (arg instanceof Message m) {
                return m;
            }
        }
        return null;
    }
}
