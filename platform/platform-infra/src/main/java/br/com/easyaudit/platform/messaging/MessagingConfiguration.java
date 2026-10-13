package br.com.easyaudit.platform.messaging;

import br.com.easyaudit.platform.PlatformDefaultsConfiguration;

import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.rabbit.config.RetryInterceptorBuilder;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer;
import org.springframework.amqp.rabbit.retry.RejectAndDontRequeueRecoverer;
import org.springframework.amqp.support.converter.JacksonJsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.boot.amqp.autoconfigure.SimpleRabbitListenerContainerFactoryConfigurer;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Mensageria da plataforma: exchanges, factory de listeners (retry exponencial, depois DLQ), propagação do tenant
 * e idempotência. Os módulos declaram as próprias filas com {@link MessagingTopology#consumerQueue}.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(MessagingProperties.class)
@Import(PlatformDefaultsConfiguration.class)
public class MessagingConfiguration {

    public static final String LISTENER_FACTORY = "eventListenerContainerFactory";

    @Bean
    Declarables eventExchanges() {
        return new Declarables(MessagingTopology.eventsExchange(), MessagingTopology.deadLetterExchange());
    }

    @Bean
    MessageConverter eventMessageConverter() {
        return new JacksonJsonMessageConverter();
    }

    @Bean(LISTENER_FACTORY)
    SimpleRabbitListenerContainerFactory eventListenerContainerFactory(
            SimpleRabbitListenerContainerFactoryConfigurer configurer, ConnectionFactory connectionFactory,
            MessagingProperties properties) {
        boolean enabled = properties.listeners().enabled();
        SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory() {
            @Override
            protected SimpleMessageListenerContainer createContainerInstance() {
                // com os consumidores desligados o container nunca inicia, nem em um start() explícito do registry
                // (por exemplo, quando o contexto é reiniciado); só o autoStartup=false não basta
                return new SimpleMessageListenerContainer() {
                    @Override
                    public void start() {
                        if (enabled) {
                            super.start();
                        }
                    }
                };
            }
        };
        configurer.configure(factory, connectionFactory);
        factory.setAutoStartup(enabled);
        factory.setDefaultRequeueRejected(false);

        MessagingProperties.Retry retry = properties.retry();
        var retryAdvice = RetryInterceptorBuilder.stateless()
                .maxRetries(retry.maxAttempts() - 1)
                .backOffOptions(retry.initialInterval().toMillis(), retry.multiplier(), retry.maxInterval().toMillis())
                .recoverer(new RejectAndDontRequeueRecoverer())
                .build();
        // tenant por fora, retry por dentro: o escopo do tenant cobre todas as tentativas. A ordem importa: o retry
        // chama invocation.proceed() de novo na mesma invocação, e os advices que vêm depois dele seriam pulados
        // nas tentativas seguintes.
        factory.setAdviceChain(new TenantPropagationAdvice(), retryAdvice);
        return factory;
    }

    @Bean
    IdempotentConsumer idempotentConsumer(JdbcClient jdbcClient, PlatformTransactionManager transactionManager) {
        return new IdempotentConsumer(jdbcClient, new TransactionTemplate(transactionManager));
    }
}
