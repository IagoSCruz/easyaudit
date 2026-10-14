# Tasks

## 1. Fundação do monorepo e ambiente local

- [x] 1.1 Confirmar e fixar as versões compatíveis com Java 25 (última GA do Spring Boot 4.x, Spring Modulith, Testcontainers, Keycloak 26.x, fastexcel, FastCSV, MapStruct, jqwik, openapi-generator 7.25.0) como properties do `pom.xml` pai. Verificar com um build vazio `./mvnw -q verify` que termina com sucesso.
- [x] 1.2 Criar o Maven Wrapper e o `pom.xml` pai com os módulos `contracts`, `platform/shared-kernel`, `platform/platform-infra`, `modules/{tenancy,documents,freight,quotes,audit,settlement}` e `app`, `maven.compiler.release=25`, enforcer (Java 25, Maven ≥ 3.9, convergência de dependências), surefire e failsafe. Verificar que `./mvnw verify` constrói todos os módulos vazios.
- [x] 1.3 Criar em `app` a `EasyAuditApplication` (pacote `br.com.easyaudit`) com Spring Modulith, virtual threads ligadas e um teste `ApplicationModules.of(...).verify()`. Verificar que o teste passa.
- [x] 1.4 Criar `deploy/local/compose.yaml` com PostgreSQL 17 (script de init criando as roles `easyaudit_owner` e `easyaudit_app`), RabbitMQ 4 com management e Keycloak 26 importando o realm `easyaudit` (client `easyaudit-api`, papéis `PLATFORM_ADMIN`, `TENANT_ADMIN`, `AUDITOR`, `FINANCE` e `VIEWER`, mapper do atributo `tenant_id` para claim, usuários de teste de 2 tenants e 1 admin de plataforma). Verificar que `docker compose up` sobe os 3 serviços saudáveis e que um token obtido com `curl` traz a claim `tenant_id`.
- [x] 1.5 Escrever o `README.md` com pré-requisitos, build, como subir o ambiente local e como obter tokens de teste. Verificar executando os comandos exatamente como estão documentados.

## 2. Contratos (contract-first)

- [x] 2.1 Configurar o módulo `contracts` com a estrutura `src/main/contracts/{schemas,openapi,asyncapi}` e duas execuções do `openapi-generator-maven-plugin`: `spring` com `interfaceOnly`, `useSpringBoot4`, `useTags`, `useBeanValidation`, `skipDefaultInterface` e `openApiNullable=false`; e `java` só com modelos para os eventos. Verificar que `./mvnw -pl contracts verify` gera e compila um endpoint de exemplo.
- [x] 2.2 Escrever `schemas/common.yaml` (Money como `number` sem format, Cnpj, AccessKey, Cep, PageMeta, Problem RFC 9457 com `code` e `errors[]`). Verificar que os tipos gerados usam `BigDecimal` para Money e que `validateSpec` passa.
- [x] 2.3 Escrever os schemas canônicos `schemas/cte.yaml` e `schemas/nfe.yaml` com todos os campos das specs `document-normalization` (tipos `NORMAL`, `COMPLEMENT` e `SUBSTITUTE`, tomador e papel, componentes com nome original e código, quantidades, pesos derivados, vínculos, status de cancelamento) e `schemaVersion`. Verificar que as classes geradas compilam.
- [x] 2.4 Escrever `schemas/events.yaml` (envelope `eventId`, `eventType`, `schemaVersion`, `occurredAt`, `tenantId`, `data`, e payloads de todos os eventos do catálogo do design), `openapi/easyaudit-events-models.yaml` e `asyncapi/easyaudit-events.yaml` (AsyncAPI 3.0 com exchange `easyaudit.events`, routing keys e mensagens). Verificar que os modelos de evento compilam e que `asyncapi validate` (CLI via imagem Docker) aceita o documento.
- [x] 2.5 Criar os utilitários de teste de contrato em `platform-infra` (test-jar): validador de payload de evento contra JSON Schema (networknt) e validador de resposta MockMvc contra o OpenAPI (swagger-request-validator). Verificar com um teste de exemplo que falha com um payload inválido e passa com um válido.

## 3. Shared kernel e infraestrutura de plataforma

- [x] 3.1 Implementar em `shared-kernel` os value objects `TenantId`, `Cnpj` (numérico e alfanumérico, raiz, DV oficial), `AccessKey` (44 dígitos, DV módulo 11, modelo, UF e CNPJ do emitente), `Cep`, `Money` e `Weight`. Verificar com testes unitários que incluem `11.222.333/0001-81`, `12.ABC.345/01DE-35` e chaves com DV inválido.
- [x] 3.2 Implementar o contexto de tenant: holder com `ScopedValue<TenantId>`, `CurrentTenantIdentifierResolver` do Hibernate e um hook que executa `set_config('app.tenant_id', ?, true)` no início de cada transação. Verificar com um teste de integração (Testcontainers) que duas transações de tenants diferentes enxergam `current_setting` corretos.
- [x] 3.3 Configurar o Flyway com `locations` por módulo, versões por timestamp, schema `messaging` e grants para `easyaudit_app`. Criar o teste de arquitetura de banco que falha se alguma tabela com `tenant_id` não tiver `FORCE ROW LEVEL SECURITY` e a política padrão. Verificar que o teste passa e falha ao remover a RLS de uma tabela de teste.
- [x] 3.4 Declarar a topologia do RabbitMQ (exchange `easyaudit.events`, DLX `easyaudit.dlx`, helper de fila quorum por consumidor com DLQ e `x-delivery-limit`), a factory de listener com retry exponencial (1 s, 5 s, 25 s) e depois a DLQ, a flag `easyaudit.messaging.listeners.enabled`, a propagação do tenant (header → `ScopedValue`) e o helper de idempotência `messaging.processed_event`. Verificar com um teste de integração que uma mensagem com falha permanente chega à DLQ depois de 3 tentativas e que o mesmo `eventId` entregue duas vezes só é processado uma vez.
- [x] 3.5 Configurar o outbox (event publication registry JDBC do Spring Modulith + externalização AMQP com routing key = `eventType`, publisher confirms, serialização do envelope gerado). Verificar com testes de integração que o evento só é publicado depois do commit, que um rollback não publica nada e que publicações incompletas são reenviadas.
- [x] 3.6 Configurar a segurança base: cadeia JWT para `/api/**` e `/admin/**`, converter de papéis do realm, exigência da claim `tenant_id` em `/api/**`, health público e handler global de `ProblemDetail`. Verificar com testes os cenários de 401 (sem token, expirado) e 403 (sem claim) da spec `tenant-access`.
- [x] 3.7 Configurar a observabilidade: logs JSON estruturados com `tenantId`, `traceId` e `eventId`, exportação OTLP configurável e observation no `RabbitTemplate` e nos listeners. Verificar com um teste que o `traceId` do produtor aparece no processamento do consumidor.
- [x] 3.8 Escrever `docs/architecture.md` com o mapa de módulos, as regras de dependência, o catálogo de eventos e as decisões de tenancy e outbox. Verificar que cada fila e routing key citadas existem na topologia declarada.

## 4. Tenancy (tenant-access)

- [x] 4.1 Adicionar ao OpenAPI os endpoints `/admin/v1/tenants`, `/api/v1/tenant/cnpjs` e `/api/v1/tenant/integration-credentials`, com os security schemes (bearer JWT e headers HMAC). Verificar que as interfaces são geradas.
- [x] 4.2 Criar as migrações e a persistência de `tenancy.tenant` (slug único) e `tenancy.tenant_cnpj` (CNPJ completo ou raiz, com unicidade global de raiz) e implementar a criação de tenant por `PLATFORM_ADMIN`. Verificar com testes de integração os cenários 201, 409 (slug duplicado) e 403 (sem papel de plataforma).
- [x] 4.3 Implementar a gestão de CNPJs pelo `TENANT_ADMIN` (incluir e remover, 422 para DV inválido, 409 para CNPJ ou raiz de outro tenant). Verificar com testes os cenários da spec, incluindo o CNPJ alfanumérico.
- [x] 4.4 Expor na `api` do módulo a fachada `TenantDirectory` (`ownsCnpj(tenant, cnpj)` considerando raízes, e resolução de credencial). Verificar com testes unitários da regra de raiz.
- [x] 4.5 Implementar as credenciais de integração: criar (segredo exibido uma única vez, cifrado com AES-256-GCM e `key_version`), listar e revogar. Verificar com testes que a listagem nunca retorna o segredo e que a credencial revogada fica `REVOKED`.
- [x] 4.6 Implementar o filtro HMAC de `/webhooks/**` (corpo bruto com limite de 20 MB e 413 acima disso, janela de ±300 s, comparação em tempo constante, tenant derivado da credencial). Verificar com testes os cenários válido, assinatura inválida, timestamp fora da janela e credencial revogada ou desconhecida.
- [x] 4.7 Criar a fixture reutilizável de dois tenants para testes de isolamento. Verificar que um tenant recebe 404 ao acessar uma credencial do outro.
- [x] 4.8 Escrever `docs/onboarding-tenant.md` (criar tenant pela API, criar usuários no Keycloak com o atributo `tenant_id` e os papéis, gerar credencial de webhook). Verificar seguindo o passo a passo no ambiente local até obter um token com a claim e uma credencial ativa.

## 5. Documentos: ingestão (document-ingestion)

- [x] 5.1 Adicionar ao OpenAPI `POST/GET /api/v1/documents`, `GET /api/v1/documents/{id}`, `GET /api/v1/documents/{id}/xml` e `POST /webhooks/v1/documents` (envelope base64), com o resultado por item e os códigos de rejeição. Verificar que as interfaces são geradas.
- [x] 5.2 Criar o gerador de fixtures de teste para CT-e 4.00, NF-e 4.00 e eventos de cancelamento, assinados com certificado autoassinado gerado no teste (RSA-SHA1, C14N, Reference para o `Id`). Verificar que as fixtures geradas passam na verificação de assinatura do JDK.
- [x] 5.3 Implementar o parser seguro de cabeçalho (StAX sem DTD nem entidades externas) e a classificação: tipo, versão, `tpAmb`, chave, `cStat`, tomador por `toma3`/`toma4`, emitente e destinatário. Verificar com testes unitários os tipos suportados e não suportados (CT-e 3.00, carta de correção) e um payload XXE rejeitado.
- [x] 5.4 Implementar a verificação de assinatura XMLDSig com a política de validação segura ajustada só para SHA-1 e a checagem de que a `Reference/@URI` aponta para o elemento assinado esperado. Verificar com testes: assinatura válida, `vTPrest` adulterado (`INVALID_SIGNATURE`) e tentativa de signature wrapping rejeitada.
- [x] 5.5 Criar as migrações e a persistência de `documents.document`, `documents.raw_document` e `documents.pending_cancellation` (constraints de unicidade, RLS) e a porta `RawDocumentStorage` com adapter PostgreSQL. Verificar que as migrações rodam e que o teste de RLS passa.
- [x] 5.6 Implementar o caso de uso `IngestDocument(tenant, source, bytes)`: cadeia de validações (`UNSUPPORTED_DOCUMENT`, `NOT_AUTHORIZED`, `INVALID_SIGNATURE`, `HOMOLOGATION_DOCUMENT` com flag de instalação, `NOT_TENANT_DOCUMENT`), idempotência (`DUPLICATE` com o id existente), cancelamento pendente e `document.received` pelo outbox. Verificar com testes de integração cada motivo de rejeição, o `DUPLICATE` e a mesma chave em tenants diferentes.
- [x] 5.7 Implementar o upload multipart (XML e ZIP, 1 MB por XML, 20 MB e 500 entradas por ZIP, proteções contra zip bomb, resultado por arquivo, 403 para `VIEWER`). Verificar com testes o cenário do ZIP misto, `FILE_TOO_LARGE` e o 403.
- [x] 5.8 Implementar o webhook de documentos (envelope com até 100 XMLs em base64 e 413 acima disso, mesmas validações e mesmo formato de resposta). Verificar com testes os cenários da spec.
- [x] 5.9 Implementar a listagem com filtros (tipo, status, chave, período), o detalhe com status e o download do original. Verificar com teste que os bytes baixados têm o mesmo SHA-256 registrado na ingestão.

## 6. Transportadoras e sinônimos (freight-tables, parte 1)

- [x] 6.1 Adicionar ao OpenAPI `/api/v1/carriers` e `/api/v1/carriers/{id}/component-aliases`. Verificar que as interfaces são geradas.
- [x] 6.2 Criar as migrações e a persistência de `freight.carrier` (CNPJ único por tenant, ativo/inativo, `autoRegistered`) e `freight.component_alias` e implementar o CRUD para `TENANT_ADMIN`. Verificar com testes o cadastro manual e o 409 para CNPJ repetido.
- [x] 6.3 Implementar o consumidor de `document.normalized` que cadastra automaticamente a transportadora emitente de CT-es desconhecidos. Verificar com um teste de integração que a transportadora é criada com `autoRegistered=true` uma única vez, mesmo com evento duplicado.
- [x] 6.4 Expor na `api` do módulo `ComponentAliasProvider` (sinônimos por transportadora). Verificar com teste unitário que retorna só os sinônimos do tenant e da transportadora pedidos.

## 7. Documentos: normalização (document-normalization)

- [x] 7.1 Implementar os DTOs de layout com Jackson XML para o subconjunto de CT-e 4.00, NF-e 4.00 e eventos de cancelamento, tolerantes a elementos desconhecidos. Verificar com testes de parse sobre as fixtures, incluindo uma com um grupo de tributo desconhecido.
- [x] 7.2 Implementar o mapeamento para o modelo canônico gerado (CT-e e NF-e) e as regras de derivação de peso e volume (`cUnid`/`tpMed`). Verificar com testes unitários os cenários da spec (PESO BRUTO e Peso Cubado, TON → kg, M3, volumes da NF-e somando 4.750 kg, campo obrigatório ausente).
- [x] 7.3 Implementar a classificação de componentes (dicionário padrão versionado e sinônimos da transportadora via `ComponentAliasProvider`, que prevalecem). Verificar com testes "FRETE PESO"/"Frete-Peso" → `FREIGHT_WEIGHT`, o sinônimo da transportadora e `OTHER` mantendo o nome original.
- [x] 7.4 Implementar o consumidor de `document.received`: normalizar, gravar o canônico em `jsonb` com as colunas-chave, `PROCESSED` e `document.normalized`. Erros de conteúdo vão direto para `FAILED` com motivo, e erros de infraestrutura são reenviados, com um handler da DLQ marcando `FAILED`. Verificar com testes de integração o caminho feliz (evento validado contra o schema) e os dois tipos de falha.
- [x] 7.5 Implementar `documents.document_link`: vínculo CT-e ↔ NF-e nas duas ordens de chegada com `document.link-resolved`, e vínculos de complementar e substituto com a marcação `SUBSTITUTED`. Verificar com testes de integração as duas ordens e o substituto.
- [x] 7.6 Implementar a aplicação de cancelamentos (imediata e pendente) com `document.cancelled`. Verificar com testes os dois cenários da spec.
- [x] 7.7 Completar o `GET /api/v1/documents/{id}` com a representação canônica e os vínculos resolvidos e pendentes, e expor na `api` do módulo as consultas de documento canônico e de vínculos usadas pela auditoria. Verificar com o teste do cenário "CT-e com 2 NF-es, uma recebida e outra não", validando a resposta contra o OpenAPI.

## 8. Tabelas de frete (freight-tables, parte 2)

- [x] 8.1 Escrever `schemas/freight-table.yaml` (formato canônico da tabela) e adicionar ao OpenAPI os endpoints de tabelas: criar por JSON, importar XLSX, baixar o modelo, editar o rascunho, arquivar, criar nova versão, ativar, listar e detalhar. Verificar que as interfaces são geradas.
- [x] 8.2 Criar as migrações e a persistência de `freight.freight_table` (metadados, status, versão, vigência, `content jsonb`, RLS). Verificar que as migrações rodam e que o teste de RLS passa.
- [x] 8.3 Implementar o validador de tabela que acumula erros com localização (CEP, faixas invertidas, sobreposição de CEP por origem, faixas de peso desde 0 sem buracos, valores negativos, percentuais, fator de cubagem). Verificar com testes unitários uma regra por teste, incluindo "faixas com buraco" e "CEPs sobrepostos".
- [x] 8.4 Implementar a criação por JSON em `DRAFT`, com 422 listando todos os erros com o caminho do campo. Verificar com o cenário da spec (CEP inválido e percentual de 150%).
- [x] 8.5 Implementar a planilha modelo (abas `Geral`, `Rotas`, `Faixas` e `AreasEspeciais` com cabeçalhos e uma linha de exemplo) e a importação XLSX com erros por aba, linha e coluna. Documentar as colunas em `docs/freight-table-template.md`. Verificar com testes o download do modelo, a reimportação do próprio modelo sem erros e o cenário do erro na linha 12, coluna B.
- [x] 8.6 Implementar o ciclo de vida (editar ou arquivar só em `DRAFT`, imutabilidade de `ACTIVE` com 409, nova versão a partir da `ACTIVE`). Verificar com testes os cenários da spec.
- [x] 8.7 Implementar a ativação (lock por transportadora, regra de início posterior, ajuste do fim de vigência da versão anterior, `freight-table.activated` com transportadora e período). Verificar com testes os cenários "nova versão" e "retroativa conflitante" (409).
- [x] 8.8 Implementar a seleção da tabela vigente (data de emissão em America/Sao_Paulo, rota por CEP de destino com prioridade para rotas com origem) e o cache Caffeine das versões compiladas, e expor na `api` do módulo. Verificar com testes unitários `NO_TABLE`, `ROUTE_NOT_COVERED` e o CT-e emitido antes da troca de tabela.

## 9. Cálculo do frete (freight-calculation)

- [x] 9.1 Implementar o `FreightCalculator` puro (peso taxado, faixas e excedente, ad valorem, GRIS, pedágio por fração, TDA/TDE, despacho, ajuste ao mínimo, ICMS por dentro, arredondamento `HALF_UP` por componente) com resultado selado `Calculated`/`RouteNotCovered`. Verificar com testes unitários que reproduzem todos os cenários da spec com a tabela T (incluindo 66.02/7.92 e `MINIMUM_ADJUSTMENT` 8.40).
- [x] 9.2 Escrever os testes de propriedade com jqwik (total = soma dos componentes, frete peso monotônico no peso, peso taxado ≥ peso real, determinismo). Verificar que rodam no `./mvnw verify`.
- [x] 9.3 Expor na `api` do módulo o cálculo de um embarque (seleção da tabela + cálculo) e implementar `POST /api/v1/freight-tables/{id}/simulations` para tabelas em `DRAFT` ou `ACTIVE`. Verificar com teste de integração que a simulação de um rascunho devolve o detalhamento sem alterar nenhum dado.

## 10. Cotações (quote-registry)

- [x] 10.1 Escrever `schemas/quote.yaml` e adicionar ao OpenAPI `POST/GET /api/v1/quotes`, `GET /api/v1/quotes/{id}` e `POST /webhooks/v1/quotes`. Verificar que as interfaces são geradas.
- [x] 10.2 Criar as migrações e a persistência de `quotes.quote` (chave idempotente tenant + origem + id externo, status do vínculo) e `quotes.quote_revision`. Verificar que as migrações rodam e que o teste de RLS passa.
- [x] 10.3 Implementar o registro (validações de chave, CNPJ e CEP, chave de NF-e ou pedido obrigatório, reenvio idêntico sem efeito, nova revisão quando o conteúdo muda) por REST e por webhook. Verificar com testes os cenários da spec.
- [x] 10.4 Implementar o vínculo por chave no registro e por `xPed` no consumidor de `document.normalized` (NF-e), a marcação `AMBIGUOUS_ORDER_REFERENCE` e os eventos `quote.linked` e `quote.revised`. Verificar com testes de integração as duas ordens de chegada e o pedido ambíguo.
- [x] 10.5 Expor na `api` do módulo a consulta da cotação aplicável (NF-e + raiz do CNPJ da transportadora, revisão vigente mais recente). Verificar com testes unitários "outra transportadora" e "filial".
- [x] 10.6 Implementar a listagem com filtros e o detalhe com revisões. Verificar com o teste do filtro `UNLINKED`.

## 11. Motor de auditoria e políticas (freight-audit)

- [x] 11.1 Escrever `schemas/audit.yaml` e adicionar ao OpenAPI os endpoints de políticas (do tenant e por transportadora) e de reauditoria manual. Verificar que as interfaces são geradas.
- [x] 11.2 Criar as migrações de `audit.policy`, `audit.audit_case` (com `version` e colunas desnormalizadas), `audit.audit_revision`, `audit.case_nfe`, `audit.case_transition` (sem UPDATE nem DELETE para `easyaudit_app`) e `audit.dispute`. Verificar que as migrações rodam, que a RLS passa e que um teste confirma que um UPDATE em `case_transition` é negado.
- [x] 11.3 Implementar as políticas: padrão do tenant quando não há registro (0.00, 0%, aprovação automática ligada, peso 5%, carga 1%), sobrescrita por transportadora campo a campo, validação 422 e acesso só para `TENANT_ADMIN`. Verificar com testes os cenários da spec.
- [x] 11.4 Implementar o `AuditEngine` puro: valor de referência da tabela e da cotação (soma só quando todas as NF-es têm cotação), regra de tolerância e `UNDERCHARGE`, verificações bloqueantes e de alerta, classificação e comparação por componente. Verificar com testes unitários que reproduzem todos os cenários da spec `freight-audit`.
- [x] 11.5 Implementar a orquestração: consumidor de `document.normalized` (CT-e) → caso idempotente → `AuditInput` montado pelas `api`s de documents, freight e quotes → advisory locks por NF-e em ordem → motor → revisão com snapshot, colunas desnormalizadas e `audit.completed`. Verificar com testes de integração: upload de CT-e até o caso classificado, notificação duplicada gerando um só caso e dois CT-es concorrentes da mesma NF-e com exatamente um `DUPLICATE_NFE`.
- [x] 11.6 Implementar a reauditoria: consumidores de `document.link-resolved`, `quote.linked`/`quote.revised` e `freight-table.activated` (paginado, uma transação por caso), endpoint manual para `AUDITOR` e guarda de estados. Verificar com testes de integração os cenários "NF-e chega depois", "tabela ativada depois dos CT-es" e "caso aprovado não é reauditado".

## 12. Workflow de auditoria (audit-workflow)

- [x] 12.1 Adicionar ao OpenAPI as ações sobre o caso (aprovar, aprovar em lote, contestar, resolver, desistir, reabrir), cada uma com a versão do caso. Verificar que as interfaces são geradas.
- [x] 12.2 Implementar a máquina de estados do domínio (tabela de transições permitidas e erro de transição inválida com o estado atual). Verificar com testes unitários todas as transições permitidas e uma amostra das proibidas.
- [x] 12.3 Aplicar a classificação ao estado do caso e a aprovação automática por política (ator `SYSTEM`, valor aprovado = cobrado) na mesma transação da auditoria. Verificar com testes os cenários com a aprovação automática ligada e desligada.
- [x] 12.4 Implementar aprovar, contestar, resolver, desistir e reabrir com as regras de valor e justificativa, lock otimista (409 para versão desatualizada), histórico append-only e `audit.status-changed`. Verificar com testes de integração cada cenário da spec, incluindo "dois auditores ao mesmo tempo".
- [x] 12.5 Implementar a aprovação em lote (até 500 casos, resultado por item). Verificar com o teste do lote com um caso `PAID`.
- [x] 12.6 Implementar o consumidor de `document.cancelled` e da substituição: `CANCELLED` com `CTE_CANCELLED` ou `CTE_SUBSTITUTED` e, se o caso já estiver `PAID`, o alerta `CANCELLED_AFTER_PAYMENT`. Verificar com testes os dois cenários da spec.
- [x] 12.7 Expor na `api` do módulo `AuditCaseCommands` (agendar, desagendar e marcar como pago, com guarda de estado, para uso transacional pela liquidação). Verificar com testes unitários as guardas, incluindo a falha para caso `CANCELLED`.

## 13. Liquidação (payment-settlement)

- [x] 13.1 Adicionar ao OpenAPI os endpoints de lotes (criar, listar, detalhar, incluir e retirar casos, exportar CSV, registrar pagamento, cancelar). Verificar que as interfaces são geradas.
- [x] 13.2 Criar as migrações e a persistência de `settlement.payment_batch`, `settlement.batch_item` e `settlement.batch_export`. Verificar que as migrações rodam e que o teste de RLS passa.
- [x] 13.3 Implementar a criação de lote (só casos `APPROVED` da mesma raiz de transportadora, 422 listando os inválidos, totais, casos em `SCHEDULED_FOR_PAYMENT` via `AuditCaseCommands`). Verificar com testes os dois cenários da spec.
- [x] 13.4 Implementar a inclusão e a retirada de casos e o cancelamento do lote, com recálculo dos totais. Verificar com testes os cenários "retirada de caso" e "cancelamento do lote".
- [x] 13.5 Implementar a exportação CSV (UTF-8 com BOM, `;`, decimais com vírgula) com registro de data e usuário. Verificar com um teste byte a byte do cenário da spec (glosas "3,00" e "0,00").
- [x] 13.6 Implementar a baixa manual (data não futura, referência, lote e casos `PAID`, imutabilidade do lote pago, 409 se algum caso tiver sido cancelado). Verificar com testes os cenários da spec.
- [x] 13.7 Implementar o consumidor de `audit.status-changed` que retira do lote `OPEN` os casos `CANCELLED` e recalcula os totais. Verificar com teste de integração.
- [x] 13.8 Implementar a listagem de lotes com filtros e o detalhe. Verificar com o teste do filtro `OPEN`.

## 14. Dashboard de auditoria (audit-dashboard)

- [x] 14.1 Adicionar ao OpenAPI `GET /api/v1/audits`, `GET /api/v1/audits/{id}`, `GET /api/v1/audits/export`, `GET /api/v1/dashboard/summary` e `GET /api/v1/dashboard/carriers`. Verificar que as interfaces são geradas.
- [x] 14.2 Implementar a listagem com `JdbcClient` (filtros, ordenação, paginação até 100 e 400 acima disso) e criar os índices do design. Verificar com testes o cenário dos divergentes de uma transportadora, o 400 e o isolamento com a fixture de dois tenants.
- [x] 14.3 Implementar o detalhe do caso (CT-e canônico, NF-es vinculadas e pendentes, cotação, revisão vigente com cálculo, comparação e verificações, revisões anteriores, histórico, contestação, lote e alertas). Verificar com o teste do caso `DISPUTED`.
- [x] 14.4 Implementar os indicadores consolidados (contagens e valores por estado, totais, divergência, economia, taxa de conformidade, zeros e nulo sem dados). Verificar com testes os números dos cenários da spec (211.02 aprovados, 8.00 de economia, 80% de conformidade).
- [x] 14.5 Implementar o ranking de transportadoras. Verificar com o teste de ordenação pela divergência.
- [x] 14.6 Implementar a exportação CSV da listagem com `count` prévio e limite configurável (padrão 50.000, 422 acima disso) e streaming. Verificar com teste usando um limite reduzido na configuração de teste.

## 15. Integração ponta a ponta e verificação final

- [x] 15.1 Escrever um teste end-to-end com Testcontainers (PostgreSQL, RabbitMQ e Keycloak reais): onboarding do tenant → credencial → NF-e e CT-e por webhook → cotação → importação e ativação da tabela → auditoria → contestação → resolução → lote → exportação → baixa → indicadores do dashboard. Verificar que passa em `./mvnw verify`.
- [x] 15.2 Escrever a suíte de isolamento que percorre todos os endpoints `GET` por id e as listagens com um usuário do tenant B contra os dados do tenant A. Verificar que todos retornam 404 ou listas vazias.
- [x] 15.3 Ligar a validação de contrato global (todas as respostas REST dos testes de integração contra o OpenAPI e todos os eventos publicados contra os schemas). Verificar que a suíte completa passa sem nenhuma violação.
- [x] 15.4 Rodar a verificação final: `ApplicationModules.verify()`, teste de RLS, `./mvnw verify` verde, `./mvnw -pl app spring-boot:build-image` gerando a imagem e `openspec validate add-freight-audit-mvp --strict`. Verificar que todos passam.
- [x] 15.5 Adicionar `docs/local-walkthrough.http` (ou equivalente com `curl`) reproduzindo o fluxo do 15.1 contra o ambiente local e referenciá-lo no README. Verificar executando o roteiro inteiro contra `docker compose up` + aplicação local.

## Workflow follow-up

- Arquivar a change (`/opsx:archive`) depois da revisão e com todas as tasks concluídas, para promover as specs para `openspec/specs/`.
- Próximas changes previstas: worker de Distribuição DF-e na SEFAZ (nova `source` em `IngestDocument`), conectores VTEX, Intelipost e Frete Rápido (tabelas e cotações), faturas de transportadoras e frontend do dashboard.
