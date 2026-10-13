package br.com.easyaudit.platform.messaging;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Propriedades {@code easyaudit.messaging.*}.
 *
 * @param listeners {@code enabled=false} desliga todos os consumidores (instâncias só de API)
 * @param retry     tentativas e backoff exponencial dos consumidores antes de rejeitar para a DLQ
 * @param deliveryLimit {@code x-delivery-limit} das filas quorum (proteção contra mensagens envenenadas)
 */
@ConfigurationProperties("easyaudit.messaging")
public record MessagingProperties(Listeners listeners, Retry retry, Integer deliveryLimit) {

    public MessagingProperties {
        listeners = listeners == null ? new Listeners(true) : listeners;
        retry = retry == null ? new Retry(null, null, null, null) : retry;
        deliveryLimit = deliveryLimit == null ? 5 : deliveryLimit;
    }

    public record Listeners(boolean enabled) {
    }

    /** Por padrão 3 tentativas no total, esperando 1 s e depois 5 s (multiplicador 5, teto de 25 s). */
    public record Retry(Integer maxAttempts, Duration initialInterval, Double multiplier, Duration maxInterval) {

        public Retry {
            maxAttempts = maxAttempts == null ? 3 : maxAttempts;
            initialInterval = initialInterval == null ? Duration.ofSeconds(1) : initialInterval;
            multiplier = multiplier == null ? 5.0 : multiplier;
            maxInterval = maxInterval == null ? Duration.ofSeconds(25) : maxInterval;
        }
    }
}
