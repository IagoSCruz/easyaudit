package br.com.easyaudit.platform.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.easyaudit.platform.testsupport.PostgresContainerSupport;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.MessageListenerContainer;
import org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Com {@code easyaudit.messaging.listeners.enabled=false} nenhum consumidor inicia, nem quando o contexto é parado e
 * reiniciado (o registry de listeners do Spring AMQP inicia todos os containers em um {@code start()} explícito).
 * Este teste não sobe RabbitMQ: se algum container tentasse iniciar, a conexão falharia.
 */
@SpringBootTest(classes = MessagingTestApplication.class, properties = {
        "spring.jpa.hibernate.ddl-auto=none", "easyaudit.messaging.listeners.enabled=false"})
class DisabledListenersIT {

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        PostgresContainerSupport.registerApplicationRoles(registry);
    }

    @Autowired
    RabbitListenerEndpointRegistry registry;
    @Autowired
    ConfigurableApplicationContext context;

    @Test
    void noContainerRunsAfterStartupOrAfterAContextRestart() {
        assertThat(registry.getListenerContainers()).isNotEmpty();
        assertThat(registry.getListenerContainers()).noneMatch(MessageListenerContainer::isRunning);

        context.stop();
        context.start();

        assertThat(registry.getListenerContainers()).noneMatch(MessageListenerContainer::isRunning);
    }
}
