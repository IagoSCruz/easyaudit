package br.com.easyaudit.platform.messaging;

import java.util.Arrays;
import java.util.List;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Declarable;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;

/**
 * Topologia do RabbitMQ: exchange {@code topic} durável {@code easyaudit.events} (a routing key é o tipo do evento),
 * DLX {@code easyaudit.dlx} e, por consumidor, uma fila quorum {@code <fila>} com a respectiva {@code <fila>.dlq}.
 */
public final class MessagingTopology {

    public static final String EVENTS_EXCHANGE = "easyaudit.events";
    public static final String DEAD_LETTER_EXCHANGE = "easyaudit.dlx";
    public static final String DLQ_SUFFIX = ".dlq";

    private MessagingTopology() {
    }

    public static TopicExchange eventsExchange() {
        return new TopicExchange(EVENTS_EXCHANGE, true, false);
    }

    public static DirectExchange deadLetterExchange() {
        return new DirectExchange(DEAD_LETTER_EXCHANGE, true, false);
    }

    /**
     * Declara a fila quorum de um consumidor, ligada ao exchange de eventos pelas routing keys informadas, com
     * dead-letter para a DLQ e {@code x-delivery-limit}. Exponha o resultado como {@code @Bean Declarables}.
     */
    public static Declarables consumerQueue(String queueName, int deliveryLimit, String... routingKeys) {
        Queue queue = QueueBuilder.durable(queueName)
                .quorum()
                .deadLetterExchange(DEAD_LETTER_EXCHANGE)
                .deadLetterRoutingKey(queueName)
                .deliveryLimit(deliveryLimit)
                .build();
        Queue dlq = QueueBuilder.durable(queueName + DLQ_SUFFIX).quorum().build();

        List<Declarable> all = new java.util.ArrayList<>();
        all.add(queue);
        all.add(dlq);
        all.add(BindingBuilder.bind(dlq).to(deadLetterExchange()).with(queueName));
        Arrays.stream(routingKeys).forEach(rk -> all.add(bind(queue, rk)));
        return new Declarables(all);
    }

    private static Binding bind(Queue queue, String routingKey) {
        return BindingBuilder.bind(queue).to(eventsExchange()).with(routingKey);
    }
}
