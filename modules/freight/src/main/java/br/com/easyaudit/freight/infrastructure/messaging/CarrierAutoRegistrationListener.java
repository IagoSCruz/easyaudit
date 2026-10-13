package br.com.easyaudit.freight.infrastructure.messaging;

import java.util.Map;

import br.com.easyaudit.contracts.events.CanonicalCte;
import br.com.easyaudit.contracts.events.EventEnvelope;
import br.com.easyaudit.freight.application.CarrierService;
import br.com.easyaudit.platform.messaging.IdempotentConsumer;
import br.com.easyaudit.platform.messaging.MessagingConfiguration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * Consumidor de {@code document.normalized}: cadastra automaticamente a transportadora emitente de CT-es cujo CNPJ
 * ainda não existe no tenant. Idempotente: o {@code eventId} é gravado em {@code messaging.processed_event} na
 * mesma transação, e o cadastro em si também ignora CNPJ repetido.
 */
@Component
class CarrierAutoRegistrationListener {

    private static final Logger log = LoggerFactory.getLogger(CarrierAutoRegistrationListener.class);

    private final CarrierService carriers;
    private final IdempotentConsumer idempotent;
    private final JsonMapper mapper;

    CarrierAutoRegistrationListener(CarrierService carriers, IdempotentConsumer idempotent, JsonMapper mapper) {
        this.carriers = carriers;
        this.idempotent = idempotent;
        this.mapper = mapper;
    }

    @RabbitListener(queues = FreightMessagingConfiguration.CARRIER_AUTO_REGISTER_QUEUE,
            containerFactory = MessagingConfiguration.LISTENER_FACTORY)
    void onDocumentNormalized(EventEnvelope envelope) {
        Map<String, Object> data = envelope.getData();
        if (!"CTE".equals(data.get("kind")) || data.get("cte") == null) {
            return; // NF-e não cadastra transportadora
        }
        CanonicalCte cte = mapper.convertValue(data.get("cte"), CanonicalCte.class);
        idempotent.runOnce(FreightMessagingConfiguration.CARRIER_AUTO_REGISTER_QUEUE, envelope.getEventId(), () -> {
            var carrier = cte.getCarrier();
            if (carrier == null || carrier.getCnpj() == null) {
                log.warn("CT-e {} sem CNPJ do emitente; transportadora não cadastrada", cte.getAccessKey());
                return;
            }
            if (carriers.registerIfAbsent(carrier.getCnpj(), carrier.getName())) {
                log.info("Transportadora {} cadastrada automaticamente", carrier.getCnpj());
            }
        });
    }
}
