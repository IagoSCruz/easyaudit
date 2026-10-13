# Arquitetura do EasyAudit

Monólito modular em Java 25 e Spring Boot 4.1, organizado com Spring Modulith e um módulo Maven por fronteira. Este documento resume o mapa de módulos, as regras de dependência, o catálogo de eventos e as decisões de tenancy e outbox. O raciocínio completo está em `openspec/changes/add-freight-audit-mvp/design.md`.

## Mapa de módulos

| Módulo Maven | Pacote | Papel |
|---|---|---|
| `contracts` | `br.com.easyaudit.contracts` | OpenAPI, AsyncAPI e JSON Schemas (fonte da verdade) e o código gerado: interfaces de controller, DTOs e payloads de evento |
| `platform/shared-kernel` | `br.com.easyaudit.shared` | Value objects puros: `TenantId`, `Cnpj`, `AccessKey`, `Cep`, `Money`, `Weight` |
| `platform/platform-infra` | `br.com.easyaudit.platform` | Contexto de tenant, segurança, topologia RabbitMQ, outbox, `ProblemDetail`, Flyway, observabilidade |
| `modules/tenancy` | `br.com.easyaudit.tenancy` | Tenants, CNPJs e credenciais de integração |
| `modules/documents` | `br.com.easyaudit.documents` | Ingestão, normalização, vínculos e cancelamentos |
| `modules/freight` | `br.com.easyaudit.freight` | Transportadoras, tabelas de frete, sinônimos e cálculo |
| `modules/quotes` | `br.com.easyaudit.quotes` | Cotações e vínculos com CT-e |
| `modules/audit` | `br.com.easyaudit.audit` | Motor de auditoria, políticas, workflow e consultas do dashboard |
| `modules/settlement` | `br.com.easyaudit.settlement` | Lotes de pagamento, exportação e baixa |
| `app` | `br.com.easyaudit` | `EasyAuditApplication`, configuração e testes de ponta a ponta |

`contracts`, `shared` e `platform` são módulos Modulith do tipo `OPEN`: qualquer módulo de negócio pode usar seus tipos. Os demais são fechados.

## Regras de dependência

- Cada módulo de negócio usa os subpacotes `api` (fachadas, DTOs e eventos públicos, a *named interface* do Modulith), `application` (casos de uso e transações), `domain` (regras) e `infrastructure` (adapters `web`, `messaging` e `persistence`).
- Um módulo só enxerga o `api` de outro. O teste `ModularityTests` (`ApplicationModules.verify()`) falha em dependência cíclica ou acesso a pacote interno.
- Um módulo só depende do jar Maven de outro quando precisa da `api` dele.
- Cada módulo acessa somente o próprio schema do PostgreSQL (`tenancy`, `documents`, `freight`, `quotes`, `audit`, `settlement`); `messaging` guarda o outbox e a idempotência.
- Comandos síncronos entre módulos chamam a `api` do outro dentro da mesma transação. Reações assíncronas usam eventos pelo RabbitMQ.

## Catálogo de eventos

O envelope tem `eventId`, `eventType`, `schemaVersion`, `occurredAt`, `tenantId` e `data`. A routing key é o `eventType`. Os contratos estão em `contracts/src/main/contracts/asyncapi/easyaudit-events.yaml`.

| Routing key | Produtor | Consumidores |
|---|---|---|
| `document.received` | documents | documents (normalização) |
| `document.normalized` | documents | freight (cadastro automático de transportadora), quotes (vínculo por `xPed`), audit (cria e audita o caso) |
| `document.link-resolved` | documents | audit (reauditoria) |
| `document.cancelled` | documents | audit (cancelamento do caso) |
| `quote.linked` | quotes | audit (reauditoria) |
| `quote.revised` | quotes | audit (reauditoria) |
| `freight-table.activated` | freight | audit (reauditoria por transportadora e período) |
| `audit.completed` | audit | integrações externas (futuro) |
| `audit.status-changed` | audit | settlement (retirada de caso cancelado do lote) |

### Topologia do RabbitMQ

- Exchange `topic` durável `easyaudit.events`; exchange de dead-letter `easyaudit.dlx`.
- Cada consumidor tem uma fila quorum própria, nomeada `<módulo>.<finalidade>`, e a respectiva `<fila>.dlq`. As filas são declaradas pelo módulo consumidor com `MessagingTopology.consumerQueue(...)`, que liga a fila às routing keys, configura o dead-letter para a DLQ e o `x-delivery-limit`.
- Consumo: retry sem estado com 3 tentativas no total e backoff exponencial (1 s e depois 5 s, teto de 25 s) e, esgotadas, rejeição para a DLQ. Os valores são configuráveis em `easyaudit.messaging.retry.*`.
- Idempotência: `messaging.processed_event (consumer, event_id)` gravada na mesma transação do efeito (`IdempotentConsumer`). O `eventId` viaja no `messageId` do AMQP.
- `easyaudit.messaging.listeners.enabled=false` desliga os consumidores, para escalar instâncias só de API e só de worker.

## Decisões de tenancy

- Banco e schema compartilhados, com `tenant_id` em toda tabela de dados de tenant.
- O tenant corrente fica em um `ScopedValue` (`TenantContext`), preenchido pelo filtro JWT (claim `tenant_id`), pelo filtro de webhook (tenant da credencial) e pelo wrapper dos listeners (header `x-tenant-id`).
- O Hibernate lê o tenant pelo `TenantIdentifierResolver`; `@TenantId` filtra e preenche a coluna.
- Defesa em profundidade com Row Level Security: toda tabela com `tenant_id` liga a política padrão com `SELECT platform.enable_tenant_rls('<schema>.<tabela>')` (`ENABLE` e `FORCE ROW LEVEL SECURITY`, política `tenant_isolation`). O `TenantAwareJpaTransactionManager` executa `set_config('app.tenant_id', ?, true)` no início de cada transação, então o valor nunca vaza entre conexões do pool. Sem tenant no contexto, nenhuma linha de tenant fica visível.
- `TenantRlsChecker` falha o build se alguma tabela com `tenant_id` não tiver RLS forçada e a política padrão.
- Duas roles: `easyaudit_owner` (dona do schema, roda o Flyway) e `easyaudit_app` (usada pela aplicação, sem `BYPASSRLS`, sem ser dona das tabelas).
- Ficam fora da RLS apenas `tenancy.tenant`, `tenancy.tenant_cnpj` e `tenancy.integration_credential`, acessadas só pelo módulo `tenancy`.

## Decisões de outbox

- Eventos `EventEnvelope` publicados durante uma transação são gravados no registro de publicações do Spring Modulith (`messaging.event_publication`) na mesma transação do efeito e enviados ao RabbitMQ somente depois do commit. Um rollback não publica nada.
- A routing key é o `eventType`. Antes de publicar, o `messageId` e o header `x-tenant-id` são preenchidos a partir do envelope e o header `__TypeId__` é removido, para que o contrato não dependa de nomes de classe Java.
- Publisher confirms ligados (`spring.rabbitmq.publisher-confirm-type=correlated`).
- Publicações incompletas são reenviadas no startup (`republish-outstanding-events-on-restart`) e por um job agendado (`easyaudit.messaging.outbox.republish-interval`, padrão 1 minuto). A entrega é pelo menos uma vez; os consumidores toleram duplicatas.

## Segurança

- `/api/**` e `/admin/**`: Resource Server JWT (realm `easyaudit`). Os papéis de `realm_access.roles` viram `ROLE_*`. `/api/**` exige a claim `tenant_id` (403 `TENANT_CLAIM_MISSING` sem ela). `/admin/**` exige `PLATFORM_ADMIN`.
- `/actuator/health` é público; qualquer outro caminho é negado. Os webhooks (`/webhooks/**`, HMAC) entram com a própria cadeia.
- Todo erro sai como `application/problem+json` (RFC 9457) com `code` estável e, nas validações, `errors[] {field, message}`.

## Observabilidade

- Logs JSON no formato ECS, com `traceId` e os campos de MDC `tenantId` e `eventId`.
- Traces via OTLP, desligados por padrão: habilite com `management.tracing.export.otlp.enabled=true` e `management.opentelemetry.tracing.export.otlp.endpoint`. A amostragem padrão é 10 % (`EASYAUDIT_TRACING_SAMPLING`).
- `RabbitTemplate` e listeners geram observations, então o `traceId` do produtor aparece no processamento do consumidor.

## Ambiente local

`deploy/local/compose.yaml` sobe PostgreSQL 17, RabbitMQ 4 e Keycloak 26. Veja o `README.md` na raiz.
