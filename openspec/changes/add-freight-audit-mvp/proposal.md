# Proposal

## Why

Lojistas que contratam várias transportadoras recebem CT-es cobrando valores que nem sempre batem com a tabela de frete negociada ou com a cotação feita no checkout (VTEX, Intelipost, Frete Rápido etc.). Os serviços de conciliação que existem são presos a uma plataforma ou limitados, então a conferência acaba sendo manual ou nem acontece, e o lojista paga frete a mais sem saber. O EasyAudit é um hub neutro: recebe os documentos fiscais, recalcula o frete esperado e entrega ao lojista um fluxo de auditoria para aprovar, contestar e liberar pagamento.

Esta primeira change entrega o **núcleo do MVP**: a base técnica (monorepo modular, contratos e mensageria) e o ciclo completo de auditoria a partir de documentos recebidos por upload ou webhook. O worker da SEFAZ e os conectores específicos de parceiros ficam para changes seguintes, mas com portas e contratos já previstos.

## What Changes

- Criar o projeto backend do zero: monorepo Maven multi-módulo, Java 25, Spring Boot 4.x, PostgreSQL, RabbitMQ e Keycloak, com ambiente local via Docker Compose.
- Adotar **contract-first**: API REST descrita em OpenAPI, eventos descritos em AsyncAPI e um **modelo canônico** de CT-e/NF-e em JSON Schema, gerando o código Java a partir desses contratos.
- Multi-tenancy SaaS com autenticação OIDC (JWT do Keycloak), papéis por tenant e credenciais HMAC por tenant para os webhooks.
- Ingestão de XML de CT-e 4.00 e NF-e 4.00 (documentos autorizados e eventos de cancelamento) por upload (XML avulso ou ZIP) e por webhook assinado, com idempotência pela chave de acesso.
- Normalização assíncrona dos XMLs para o modelo canônico e vínculo CT-e ↔ NF-e pelas chaves referenciadas.
- Cadastro de transportadoras e tabelas de frete versionadas por vigência (importação por planilha XLSX padrão ou JSON canônico), com simulação de cálculo.
- Motor de cálculo do frete esperado: faixas de peso, excedente por kg, peso cubado, ad valorem, GRIS, pedágio, TDA/TDE, taxa de despacho, frete mínimo e ICMS.
- Registro de cotações do checkout (API e webhook genéricos) vinculadas à NF-e ou ao pedido.
- Motor de auditoria: compara o valor cobrado no CT-e com o valor recalculado pela tabela e com a cotação, aplicando políticas de tolerância (margem de erro) por tenant e por transportadora, e roda verificações complementares (NF-e cobrada em duplicidade, divergência de peso e de valor de carga, CT-e complementar, entre outras).
- Workflow de auditoria: aprovar (inclusive automaticamente quando o CT-e está dentro da tolerância), contestar, resolver a contestação, reabrir e cancelar, com histórico imutável de cada transição.
- Liquidação: lotes de pagamento por transportadora, exportação CSV para o financeiro/ERP e baixa manual. Nenhum dinheiro é movimentado pela plataforma.
- APIs do dashboard de auditoria: listagem filtrável, detalhe com o cálculo componente a componente, indicadores consolidados e ranking de transportadoras.

## Capabilities

### New Capabilities
- `tenant-access`: provisionamento de tenants e dos seus CNPJs, autenticação OIDC, papéis e isolamento entre tenants, e credenciais HMAC para integrações.
- `document-ingestion`: recebimento de XMLs fiscais por upload e webhook, validação de entrada, idempotência, verificação de autorização e de assinatura, posse do documento pelo tenant e armazenamento do original.
- `document-normalization`: conversão de CT-e/NF-e e eventos de cancelamento para o modelo canônico, vínculo entre documentos e mapeamento dos componentes de valor do CT-e.
- `freight-tables`: cadastro de transportadoras e ciclo de vida das tabelas de frete (importação, validação, versionamento, vigência, ativação).
- `freight-calculation`: cálculo determinístico do frete esperado a partir de uma tabela e dos dados do embarque, com detalhamento por componente.
- `quote-registry`: registro idempotente das cotações do checkout e vínculo com NF-e/pedido.
- `freight-audit`: execução da auditoria de cada CT-e (valor de referência, verificações, classificação) e políticas de tolerância.
- `audit-workflow`: estados e transições do caso de auditoria (aprovar, contestar, resolver, reabrir, cancelar) e trilha de histórico.
- `payment-settlement`: lotes de pagamento, exportação e baixa manual dos CT-es aprovados.
- `audit-dashboard`: consultas e indicadores para o painel de auditoria.

### Modified Capabilities
<!-- Nenhuma: não há specs existentes no projeto. -->

## Impact

- **Código**: repositório novo. Hoje o diretório só tem a configuração do OpenSpec; todo o código, os contratos, as migrações e a infraestrutura local serão criados nesta change.
- **APIs**: nova API REST `/api/v1/**` (JWT), endpoints de webhook `/webhooks/v1/**` (HMAC) e API administrativa `/admin/v1/**`. Todos são contratos públicos versionados a partir do primeiro release.
- **Mensageria**: exchange de eventos de domínio no RabbitMQ com filas por consumidor, retry e DLQ.
- **Dados**: banco PostgreSQL com um schema por módulo e migrações Flyway.
- **Dependências externas**: Keycloak (IdP), PostgreSQL, RabbitMQ. O build usa Maven Wrapper (não há Maven instalado globalmente) e o JDK 25 (Corretto 25 já está disponível no ambiente).
- **Fora do escopo** (próximas changes): worker de Distribuição DF-e na SEFAZ com certificado A1, conectores VTEX, Intelipost e Frete Rápido, faturas de transportadoras, integração bancária ou com ERP, frontend do dashboard, notificações e várias tabelas por serviço da mesma transportadora.
