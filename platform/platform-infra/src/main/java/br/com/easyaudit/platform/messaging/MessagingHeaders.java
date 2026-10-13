package br.com.easyaudit.platform.messaging;

/** Cabeçalhos das mensagens de evento. O {@code eventId} viaja no {@code messageId} padrão do AMQP. */
public final class MessagingHeaders {

    public static final String TENANT_ID = "x-tenant-id";

    private MessagingHeaders() {
    }
}
