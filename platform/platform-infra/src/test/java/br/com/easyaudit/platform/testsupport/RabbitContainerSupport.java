package br.com.easyaudit.platform.testsupport;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.testcontainers.rabbitmq.RabbitMQContainer;

/** RabbitMQ 4 compartilhado entre os testes de integração da JVM. */
public final class RabbitContainerSupport {

    private static final RabbitMQContainer RABBIT = new RabbitMQContainer("rabbitmq:4-management");

    static {
        RABBIT.start();
    }

    private RabbitContainerSupport() {
    }

    public static RabbitMQContainer container() {
        return RABBIT;
    }

    public static void register(DynamicPropertyRegistry registry) {
        registry.add("spring.rabbitmq.host", RABBIT::getHost);
        registry.add("spring.rabbitmq.port", RABBIT::getAmqpPort);
        registry.add("spring.rabbitmq.username", RABBIT::getAdminUsername);
        registry.add("spring.rabbitmq.password", RABBIT::getAdminPassword);
    }
}
