# Design

## Context

- **Greenfield.** O repositório só tem a configuração do OpenSpec. Não há código, convenções nem specs anteriores para respeitar. A motivação está em `proposal.md` (Why), e os requisitos estão nas specs desta change.
- **Restrições definidas pelo usuário:** Java 25, Spring Boot, Maven, monorepo modular que possa evoluir para hexagonal, RabbitMQ para mensageria, PostgreSQL, contract-first.
- **Decisões de produto já fechadas:** escopo "MVP núcleo" (sem SEFAZ e sem conectores de parceiros); "pagar" significa só workflow e baixa manual; a auditoria compara com a tabela e com a cotação; o SaaS é multi-tenant com Keycloak (OIDC/JWT) e webhooks assinados com HMAC.
- **Ambiente:** o JDK 25 (Corretto 25.0.4) está instalado. Não há Maven global, então o build usa Maven Wrapper.
- **Verificado no Context7:** o `openapi-generator-maven-plugin` 7.25.0 tem a opção `useSpringBoot4` (que também habilita `useJakartaEe`). As versões de Spring Boot 4.x, Spring Modulith e Keycloak não puderam ser confirmadas nesta sessão (cota do Context7) e são verificadas na primeira task.

## Goals / Non-Goals

**Goals:**
- Fronteiras de módulo verificáveis no build, para que extrair um serviço ou migrar um módulo para hexagonal não exija reescrever o resto.
- Contratos (REST, eventos e modelo canônico) como fonte da verdade. O código Java é gerado a partir deles, e testes garantem que a implementação respeita os contratos.
- Núcleo de cálculo e de auditoria em Java puro e determinístico, testável sem Spring nem banco.
- Isolamento de tenant garantido no banco, não só na aplicação.
- Processamento assíncrono confiável: outbox transacional, retry, DLQ e consumidores idempotentes.
- Portas de entrada prontas para as próximas changes: worker SEFAZ como nova origem de documentos; conectores de parceiros como novas origens de tabela e de cotação.

**Non-Goals:**
- Microserviços ou deploys separados por módulo. Haverá um único artefato, com papéis de execução configuráveis.
- Hexagonal "completo", com modelo de persistência separado do modelo de domínio em todos os módulos. Só o núcleo de cálculo e de auditoria fica livre de framework agora.
- Validação da cadeia ICP-Brasil do certificado que assina os XMLs. Nesta change só verificamos a integridade da assinatura.
- Sincronizar tenants e usuários com o Keycloak. Usuários são criados no Keycloak, com o atributo `tenant_id`, por processo manual documentado.
- Armazenamento de objetos (S3). O XML original fica no PostgreSQL, atrás de uma porta substituível.
- Várias tabelas por serviço da mesma transportadora e tabelas com preço por kg dentro da faixa. Faixas têm valor fixo mais excedente por kg.

## Decisions

### D1. Monólito modular em Maven multi-módulo + Spring Modulith

```
easyaudit/
├── pom.xml                    # parent: versões, dependencyManagement, pluginManagement
├── mvnw, .mvn/
├── contracts/                 # OpenAPI, AsyncAPI, JSON Schemas → código gerado (jar)
├── platform/
│   ├── shared-kernel/         # value objects puros: TenantId, Cnpj, AccessKey, Cep, Money, Weight
│   └── platform-infra/        # tenant context, segurança, topologia RabbitMQ, outbox, ProblemDetail, observabilidade
├── modules/
│   ├── tenancy/               # tenants, CNPJs, credenciais de integração
│   ├── documents/             # ingestão + normalização + vínculos + cancelamentos
│   ├── freight/               # transportadoras, tabelas, sinônimos, cálculo
│   ├── quotes/                # cotações e vínculos
│   ├── audit/                 # motor, políticas, workflow, consultas do dashboard
│   └── settlement/            # lotes de pagamento, exportação, baixa
├── app/                       # @SpringBootApplication, configuração, testes end-to-end
└── deploy/local/              # compose.yaml, realm do Keycloak, scripts de seed
```

- Pacote base `br.com.easyaudit`. Cada módulo usa `br.com.easyaudit.<modulo>` com os subpacotes `api` (fachadas, DTOs e eventos públicos; é a *named interface* do Modulith), `application` (casos de uso e transações), `domain` (regras) e `infrastructure` (adapters `web`, `messaging`, `persistence`). Essa divisão já mapeia para portas e adapters: no futuro, "virar hexagonal" é mover e isolar, sem redesenhar.
- O Spring Modulith (`ApplicationModules.of(EasyAuditApplication.class).verify()` em teste) barra dependências cíclicas e o acesso a pacotes internos de outro módulo. Os jars Maven reforçam a separação: um módulo só depende do jar de outro módulo quando precisa da `api` dele.
- **Alternativas consideradas:** microserviços (custo operacional prematuro para um MVP de time pequeno); módulo Maven único com pacotes (fronteiras só por convenção, fácil de erodir).

### D2. Stack e versões

- Java 25 (records, sealed interfaces, pattern matching, `ScopedValue` final no JDK 25, virtual threads ligadas com `spring.threads.virtual.enabled=true`).
- Spring Boot 4.x (última GA no início da implementação), Spring Modulith compatível com ela, Hibernate 7 (gerenciado pelo Boot), Jackson 3 (padrão do Boot 4), Flyway, Spring AMQP, Spring Security OAuth2 Resource Server.
- PostgreSQL 17, RabbitMQ 4.x (quorum queues), Keycloak 26.x.
- Bibliotecas: openapi-generator-maven-plugin 7.25.0 (`spring`, `useSpringBoot4`); `jackson-dataformat-xml` (parsing de CT-e/NF-e); `fastexcel` da dhatim (leitura e escrita de XLSX); FastCSV (exportações); MapStruct (DTO ↔ aplicação); jqwik (testes de propriedade); Testcontainers; `networknt json-schema-validator` (testes de contrato de eventos); `swagger-request-validator` (testes de contrato REST).
- As versões ficam fixadas no `pom.xml` pai. A task 1.1 confirma a compatibilidade com Java 25 e Boot 4 antes de seguir.

### D3. Contract-first: OpenAPI + AsyncAPI + JSON Schema compartilhado

```
contracts/src/main/contracts/
├── schemas/            # canônicos e compartilhados (YAML/JSON Schema)
│   ├── common.yaml     #   Money, Cnpj, AccessKey, Cep, Problem, PageMeta
│   ├── cte.yaml        #   CanonicalCte, CteComponent, CargoQuantity
│   ├── nfe.yaml        #   CanonicalNfe, Volume
│   ├── freight-table.yaml
│   ├── quote.yaml
│   ├── audit.yaml      #   AuditCase, AuditRevision, CheckResult, Transition
│   └── events.yaml     #   EventEnvelope + payloads de cada evento
├── openapi/easyaudit-api.yaml        # OpenAPI 3.0.3, $ref em schemas/
├── openapi/easyaudit-events-models.yaml  # só components, $ref nos payloads (para gerar modelos)
└── asyncapi/easyaudit-events.yaml    # AsyncAPI 3.0: exchange, routing keys, mensagens ($ref em schemas/)
```

- **Geração:** a primeira execução do generator `spring` (`interfaceOnly`, `useSpringBoot4`, `useTags`, `useBeanValidation`, `skipDefaultInterface`, `openApiNullable=false`) gera as interfaces de controller e os DTOs em `br.com.easyaudit.contracts.api`. A segunda, `java` só com modelos (`generateApis=false`) a partir de `easyaudit-events-models.yaml`, gera os payloads de evento em `br.com.easyaudit.contracts.events`. Os controllers de cada módulo implementam as interfaces geradas.
- **Por que OpenAPI 3.0.3 e não 3.1:** o suporte do generator ao 3.0.3 é mais maduro. Os schemas são escritos no subconjunto comum aos dois, então migrar para 3.1 depois é trivial.
- **Por que gerar eventos via OpenAPI e não por um gerador AsyncAPI:** é um só gerador e um só estilo de modelo. O AsyncAPI continua sendo o contrato publicado de canais e mensagens e aponta para os mesmos schemas.
- **Valores monetários:** `type: number` sem `format`, que vira `BigDecimal`. Datas: `date-time` com offset. Enums em inglês e textos em português.
- **Erros:** RFC 9457 (`application/problem+json`) com as extensões `code` e `errors[] {field, message}`, definido em `common.yaml`.
- **Garantias:** o build valida as specs (`validateSpec`). Os testes de integração validam respostas reais contra o OpenAPI e cada evento publicado contra o JSON Schema do payload. O envelope de evento tem `eventId`, `eventType`, `schemaVersion`, `occurredAt`, `tenantId` e `data`.
- **Alternativas:** code-first com springdoc (perde o contrato como fonte da verdade); Avro ou Protobuf nos eventos (mais rígido, exige schema registry e não conversa com o restante em JSON).

### D4. Mensageria: RabbitMQ com outbox do Spring Modulith

- **Topologia:** exchange `topic` durável `easyaudit.events`; a routing key é o tipo do evento. Uma fila quorum por consumidor, com DLX `easyaudit.dlx` → `<fila>.dlq` e `x-delivery-limit` como proteção contra mensagens envenenadas. A topologia é declarada em `platform-infra`.
- **Publicação:** os eventos de domínio que cruzam módulos são `@Externalized` e passam pelo *event publication registry* JDBC do Spring Modulith, que é o outbox transacional: o evento é gravado na mesma transação do efeito e publicado no RabbitMQ depois do commit. Publicações incompletas são reenviadas no startup e por um job agendado. Publisher confirms ligados.
- **Consumo:** `@RabbitListener` com retry sem estado (3 tentativas, backoff exponencial de 1 s, 5 s e 25 s) e depois rejeição para a DLQ. Idempotência por tabela `messaging.processed_event(consumer, event_id)` gravada na mesma transação do efeito, além de constraints naturais (por exemplo, um caso por CT-e).
- **Ordem:** não existe ordem global. Os handlers leem o estado atual do banco em vez de confiar no payload para decidir, então eventos fora de ordem convergem para o mesmo resultado.
- **Papéis de execução:** uma única imagem. `easyaudit.messaging.listeners.enabled=false` desliga os consumidores, para escalar instâncias só de API e só de worker em separado.
- **Comunicação dentro do processo:** reações dentro de um módulo usam eventos de aplicação em memória. Comandos síncronos entre módulos (por exemplo, a liquidação mudando o estado de casos) chamam a `api` do outro módulo dentro da mesma transação.
- **Alternativas:** outbox próprio com poller (reinventa o que o Modulith já dá); CDC com Debezium (infra pesada demais para o MVP); usar só eventos em memória (perde durabilidade, DLQ e escala separada, e o usuário pediu RabbitMQ).

**Catálogo de eventos:**

| Routing key | Produtor | Consumidores |
|---|---|---|
| `document.received` | documents | documents (normalização) |
| `document.normalized` | documents | freight (cadastro automático de transportadora), quotes (vínculo por `xPed`), audit (cria e audita o caso) |
| `document.link-resolved` | documents | audit (reauditoria) |
| `document.cancelled` | documents | audit (cancelamento do caso) |
| `quote.linked`, `quote.revised` | quotes | audit (reauditoria) |
| `freight-table.activated` | freight | audit (reauditoria por transportadora e período) |
| `audit.completed` | audit | integrações externas (futuro) |
| `audit.status-changed` | audit | settlement (retirada de caso cancelado do lote) |

### D5. Multi-tenancy: discriminador + Hibernate `@TenantId` + Row Level Security

- **Modelo:** banco e schema compartilhados, com coluna `tenant_id` em toda tabela de dados de tenant e `tenant_id` como primeira coluna dos índices.
- **Aplicação:** o tenant fica em um `ScopedValue<TenantId>`, preenchido pelo filtro de segurança (claim `tenant_id` do JWT), pelo filtro de webhook (tenant da credencial) e pelo wrapper dos listeners (header do evento). O `CurrentTenantIdentifierResolver` do Hibernate lê esse valor, e `@TenantId` filtra e preenche automaticamente.
- **Banco (defesa em profundidade):** RLS com `FORCE ROW LEVEL SECURITY` em todas as tabelas de tenant, com a política `tenant_id = current_setting('app.tenant_id')::uuid`. Na abertura de cada transação, `set_config('app.tenant_id', ?, true)` é executado. Isso protege também as consultas SQL nativas do dashboard, que o `@TenantId` não cobre. A aplicação conecta com a role `easyaudit_app` (não é dona das tabelas, não tem `BYPASSRLS` e não tem UPDATE nem DELETE no histórico). O Flyway roda com `easyaudit_owner`.
- **Fora da RLS:** `tenancy.tenant`, `tenancy.tenant_cnpj` e `tenancy.integration_credential`. Elas são consultadas antes de existir um tenant no contexto (administração da plataforma, lookup por `keyId`) e só são acessadas pelo módulo `tenancy`.
- **Alternativas:** um schema por tenant (migrações N vezes e pool de conexões complicado); um banco por tenant (caro). Também foi considerado só `@TenantId`, mas ele não cobre SQL nativo e uma falha vira vazamento.

### D6. Segurança

- **Duas cadeias de filtros:** `/api/**` e `/admin/**` com Resource Server JWT (`issuer-uri` do realm `easyaudit`); `/webhooks/**` com o filtro HMAC; `/actuator/health/**` público.
- **Papéis:** os papéis do realm (`realm_access.roles`) viram `ROLE_*` por um `JwtAuthenticationConverter` próprio. `PLATFORM_ADMIN` não tem `tenant_id` e só acessa `/admin/**`.
- **Filtro HMAC:** lê o corpo bruto uma vez (limite de 20 MB, acima disso 413), valida o timestamp (±300 s), busca a credencial por `keyId`, calcula `HMAC-SHA256(secret, ts + "." + body)` e compara com `MessageDigest.isEqual`. Depois recoloca o corpo na requisição.
- **Segredos de credencial:** são necessários em claro para verificar o HMAC, então não podem ser hash. Ficam cifrados com AES-256-GCM e chave mestra vinda do ambiente (`EASYAUDIT_CREDENTIALS_KEY`), com `key_version` para rotação.
- **XML seguro:** DTD e entidades externas desligadas (XXE), sem `XInclude`. **ZIP:** no máximo 500 entradas, 1 MB descompactado por entrada e 100 MB no total; diretórios ignorados; tudo processado em memória, sem extrair para disco (sem *zip slip*).
- **Assinatura XML:** validação DOM com `javax.xml.crypto`. A `Reference/@URI` tem de apontar para o `Id` do elemento assinado esperado (`infCte`, `infNFe` ou `infEvento`), para barrar *signature wrapping*. Veja o risco sobre RSA-SHA1.

### D7. Pipeline de documentos

1. **Síncrono (HTTP):** limite de tamanho → parse StAX seguro só do cabeçalho (raiz, versão, `tpAmb`, chave, `cStat`, CNPJs de posse) → classificação → verificação de assinatura → checagem de posse → idempotência (`UNIQUE (tenant_id, kind, access_key)` e, para eventos, `(tenant_id, access_key, event_type, seq)`) → grava o original (`bytea` com SHA-256) e o registro `RECEIVED`, e enfileira `document.received` na mesma transação.
2. **Assíncrono (normalização):** mapeamento com Jackson XML para DTOs de layout internos (só o subconjunto necessário, com `FAIL_ON_UNKNOWN_PROPERTIES=false` para tolerar grupos novos de notas técnicas) → modelo canônico gerado do contrato → grava o canônico em `jsonb` e as colunas-chave → vínculos → aplica cancelamentos pendentes → `PROCESSED` → eventos.
3. **Pesos (`infQ`):** `cUnid` 01 é kg, 02 é ton ×1000 e 00 é m³. O `tpMed` é normalizado (maiúsculas e sem acento): se contém `CUBAD`, é peso cubado; se contém `BRUTO`, `REAL`, `AFERIDO` ou `DECLARADO`, é peso real; com vários valores reais, usa-se o maior; sem nenhum classificado, usa-se o primeiro em kg que não é cubado.
4. **Componentes:** um dicionário padrão versionado em código (nome normalizado → código), mais sinônimos por transportadora vindos do módulo `freight` pela `api`.
5. **Porta de entrada:** o caso de uso `IngestDocument` recebe `(tenant, source, bytes)`, com `source` ∈ {`UPLOAD`, `WEBHOOK`} agora e `SEFAZ_DFE` na change do worker.
- **Alternativa ao Jackson XML:** JAXB gerado dos XSDs oficiais. Foi descartado porque é estrito, pesado e muda a cada nota técnica, quando só precisamos de um subconjunto.

### D8. Persistência

- **Schemas:** `tenancy`, `documents`, `freight`, `quotes`, `audit`, `settlement` e `messaging` (registry do Modulith e `processed_event`). Cada módulo só acessa o próprio schema.
- **Flyway:** uma única configuração com `locations` por módulo (`classpath:db/migration/<modulo>`) e versões por timestamp (`V202610130900__audit_create_case.sql`), para que módulos diferentes nunca colidam.
- **Tipos:** dinheiro `NUMERIC(15,2)`; pesos `NUMERIC(12,3)`; percentuais `NUMERIC(7,4)`; ids `uuid` (v7, ordenável); auditoria `created_at`/`updated_at` com `timestamptz`.
- **Tabelas principais:**
  - `documents.document` (tipo, chave, status, emissão, `canonical jsonb`, `raw_sha256`), `documents.raw_document` (`bytea`), `documents.document_link` (de, para, tipo do vínculo, resolvido) e `documents.pending_cancellation`.
  - `freight.carrier`, `freight.component_alias`, `freight.freight_table` (metadados, status, vigência, versão, `content jsonb`).
  - `quotes.quote`, `quotes.quote_revision`.
  - `audit.policy` (do tenant ou da transportadora), `audit.audit_case` (estado, `version`, colunas desnormalizadas para o dashboard: transportadora, emissão, UF de destino, cobrado, referência, divergência e `failed_checks text[]`), `audit.audit_revision` (snapshot imutável em `jsonb`), `audit.case_transition` (append-only), `audit.case_nfe` (índice para duplicidade) e `audit.dispute`.
  - `settlement.payment_batch`, `settlement.batch_item`, `settlement.batch_export`.
- **XML original:** atrás da porta `RawDocumentStorage`, com adapter PostgreSQL. A troca para S3 é uma change futura.

### D9. Tabelas de frete em `jsonb` imutável + cache em memória

- **Armazenamento:** o conteúdo validado (formato canônico do contrato) é gravado em `jsonb`. Como versões `ACTIVE` são imutáveis, cada versão é carregada uma vez em um cache (Caffeine, chave `tableVersionId`) como estrutura otimizada: rotas ordenadas por início de CEP com busca binária, e prioridade para rotas que também casam com a origem.
- **Importação:** a XLSX (abas `Geral`, `Rotas`, `Faixas` e `AreasEspeciais`) e o JSON caem no mesmo validador, que acumula todos os erros com a localização (caminho JSON ou aba/linha/coluna).
- **Ativação:** roda sob `SELECT … FOR UPDATE` nas versões da transportadora (ativações concorrentes são serializadas), ajusta o fim de vigência da versão anterior e publica `freight-table.activated` com transportadora e período.
- **Alternativa:** modelo totalmente relacional (rota, faixa, área) com índice GiST em `int4range`. Só compensa com tabelas enormes ou consultas por SQL, o que o MVP não tem.
- **Porta de entrada:** o caso de uso `ImportFreightTable(tenant, carrier, canonicalTable, source)` também vai servir aos conectores de parceiros.

### D10. Núcleo de cálculo e de auditoria como funções puras

- **Cálculo:** `FreightCalculator.calculate(TableVersion, ShipmentInput)` devolve `CalculationResult`, uma interface selada com `Calculated(breakdown)` e `RouteNotCovered`. Tudo em `BigDecimal`: as divisões intermediárias usam escala 10 com `HALF_UP`, e cada componente é arredondado para 2 casas com `HALF_UP` (regras em `freight-calculation`).
- **Auditoria:** `AuditEngine.evaluate(AuditInput)` devolve `AuditOutcome`. O `AuditInput` é um snapshot imutável: CT-e canônico, NF-es, cotações aplicáveis, resultado do cálculo, política efetiva e CT-es concorrentes pela mesma NF-e. Cada verificação implementa `AuditCheck` (interface selada) e devolve `CheckResult(code, severity, outcome, details)`.
- **Quem orquestra:** o serviço de aplicação do `audit` monta o `AuditInput` pelas `api`s de `documents`, `freight` e `quotes`, chama o motor, grava a revisão e aplica a transição e a aprovação automática, tudo em uma única transação.
- **Testes:** unitários com os exemplos das specs e testes de propriedade (jqwik): total igual à soma dos componentes; frete peso monotônico no peso; determinismo; peso taxado ≥ peso real.

### D11. Concorrência na auditoria

- **Duplicidade:** dois CT-es da mesma NF-e auditados ao mesmo tempo poderiam passar os dois na verificação `DUPLICATE_NFE`. Por isso, a transação de auditoria pega `pg_advisory_xact_lock(hash(tenant_id, nfe_key))` para cada NF-e referenciada, em ordem crescente de chave (evita deadlock), antes de ler `audit.case_nfe`.
- **Caso:** `@Version` (lock otimista) para as ações de usuário, que mandam a versão vista (spec `audit-workflow`). A reauditoria pega `SELECT … FOR UPDATE` no caso e só age nos estados permitidos.
- **Reauditoria em massa:** a ativação de uma tabela gera reauditorias em páginas de 200 casos, cada caso na sua própria transação, para não segurar locks longos.

### D12. Workflow como máquina de estados explícita

- **Implementação:** um `enum` de estados com uma tabela de transições permitidas no domínio. Spring Statemachine é desnecessário para cerca de 10 estados e acopla o domínio ao framework.
- **Histórico:** `audit.case_transition` é append-only, sem `UPDATE` nem `DELETE` para a role da aplicação, então a imutabilidade é garantida pelo banco.
- **Liquidação:** chama `AuditCaseCommands` (`api` do `audit`) para mudar o estado dos casos na mesma transação do lote. Se um caso já estiver `CANCELLED`, a operação inteira falha com 409, e o usuário recarrega o lote.

### D13. Consultas do dashboard direto nas tabelas de auditoria

- **Implementação:** `JdbcClient` com SQL sobre `audit.audit_case` (colunas desnormalizadas e índices `(tenant_id, status, emission_date)`, `(tenant_id, carrier_id, emission_date)` e GIN em `failed_checks`), protegido pela RLS.
- **Exportação CSV:** primeiro um `count` (limite de 50.000), depois streaming com `StreamingResponseBody`.
- **Consistência:** como os dados estão no mesmo banco, é imediata. A janela de 60 s da spec deixa espaço para um read model ou materialized views no futuro, sem quebrar o contrato.

### D14. Observabilidade e operação

- **Logs e métricas:** Actuator (health com Postgres e RabbitMQ), Micrometer e OpenTelemetry (OTLP); observation ligada no `RabbitTemplate` e nos listeners, para o trace atravessar a fila. Logs estruturados em JSON com `tenantId`, `traceId` e `eventId`.
- **Métricas de negócio:** documentos por status e motivo de rejeição, auditorias por classificação, latência de ingestão até a auditoria e reenvios do outbox.
- **Ambiente local:** `deploy/local/compose.yaml` (PostgreSQL 17, RabbitMQ 4 com management, Keycloak 26 importando o realm `easyaudit` com client, papéis e usuários de teste de dois tenants). Imagem gerada com `spring-boot:build-image`.

## Risks / Trade-offs

- **[RSA-SHA1 bloqueado pela validação segura de XMLDSig do JDK]** O JDK recente recusa assinaturas SHA-1 com *secure validation* ligada, e CT-e e NF-e são assinados com RSA-SHA1. → Mitigação: ajustar `jdk.xml.dsig.secureValidationPolicy` removendo só as restrições de SHA-1 e mantendo os demais limites, nunca desligar a validação segura inteira. Um teste com XML real assinado em SHA-1 cobre isso.
- **[Compatibilidade Boot 4 / Modulith / generator com Java 25]** → A task 1.1 fixa e verifica as versões antes de qualquer outro código. Se o `useSpringBoot4` gerar algo incompatível, o fallback é `useSpringBoot3` + `useJakartaEe`, ajustando manualmente as importações.
- **[Tabelas reais de transportadoras fora do modelo suportado]** (preço por kg na faixa, ICMS por região, vários serviços) → Esses casos ficam `NOT_AUDITABLE` com motivo explícito em vez de um valor errado. O modelo canônico já tem `schemaVersion` para evoluir.
- **[Cotação com base diferente do CT-e]** (cotação sem ICMS, frete subsidiado) → A comparação usa a mesma tolerância e o detalhe mostra os dois valores. Se precisar, uma flag de política por transportadora para ligar ou desligar `TOTAL_VS_QUOTE` pode entrar numa change futura.
- **[Entrega pelo menos uma vez e fora de ordem]** → Consumidores idempotentes (`processed_event` mais constraints naturais) e handlers que leem o estado atual.
- **[Externalização do Modulith marcar como concluído algo que o broker não persistiu]** → Publisher confirms com `RabbitTemplate` síncrono no externalizador e alerta quando há publicações incompletas por mais de 5 min.
- **[RLS esquecida em tabela nova]** → Teste de arquitetura de banco que falha se uma tabela com `tenant_id` não tiver RLS forçada.
- **[XML no PostgreSQL crescendo]** (~10 KB por documento) → Porta `RawDocumentStorage` pronta para S3, e o TOAST comprime. Particionar ou mover fica para quando houver volume real.
- **[`tenant_id` ausente no token por configuração errada do Keycloak]** → O realm exportado é versionado no repositório, há um teste end-to-end com Keycloak real (Testcontainers), e a API responde 403 explícito.
- **[CNPJ alfanumérico]** (emitido desde julho de 2026) → O value object `Cnpj` implementa o DV oficial (valor ASCII − 48, módulo 11) e é testado com os vetores da Receita. As colunas de CNPJ são `varchar(14)`, nunca numéricas.
- **[Fuso horário]** → A emissão é guardada com offset. Toda regra baseada em data (vigência, filtros de período) converte para `America/Sao_Paulo`.

## Migration Plan

Projeto novo, sem dados para migrar.

1. Provisionar PostgreSQL (roles `easyaudit_owner` e `easyaudit_app`), RabbitMQ e Keycloak (importar o realm `easyaudit`).
2. Subir a aplicação: o Flyway roda no startup com a role dona e declara a topologia do RabbitMQ.
3. Criar o primeiro tenant pela API `/admin/v1/tenants` e os usuários no Keycloak com o atributo `tenant_id`.
4. **Rollback:** voltar para a imagem anterior. As migrações são só para frente e compatíveis com a versão anterior (expand/contract) a partir do primeiro release.

## Open Questions

- Versões definitivas de produção e provedor de hospedagem (serviços gerenciados de PostgreSQL e RabbitMQ). Não mudam o código, só a configuração.
- Política de retenção e arquivamento do XML original além do mínimo legal de 5 anos. Afeta só a porta de armazenamento.
- `groupId` e pacote base (`br.com.easyaudit`) definitivos: um rename mecânico, se a marca mudar.
