# Spec Delta

## Purpose

Define como lojistas (tenants) são provisionados, como usuários e integrações se autenticam e como os dados de um tenant ficam isolados dos demais em todas as APIs, webhooks e processamentos assíncronos.

## ADDED Requirements

### Requirement: Provisionamento de tenant
O sistema SHALL permitir que um usuário com papel `PLATFORM_ADMIN` crie um tenant informando nome, identificador curto único (`slug`) e ao menos um CNPJ. Cada tenant MUST receber um identificador imutável.

#### Scenario: Criação de tenant válida
- **WHEN** um `PLATFORM_ADMIN` cria o tenant "Loja Exemplo" com slug `loja-exemplo` e o CNPJ `11.222.333/0001-81`
- **THEN** o sistema responde 201 com o identificador do tenant, e o slug e o CNPJ ficam registrados

#### Scenario: Slug duplicado
- **WHEN** um `PLATFORM_ADMIN` tenta criar um tenant com um slug que já existe
- **THEN** o sistema responde 409 e nenhum tenant é criado

#### Scenario: Usuário sem papel de plataforma
- **WHEN** um usuário sem o papel `PLATFORM_ADMIN` tenta criar um tenant
- **THEN** o sistema responde 403

### Requirement: CNPJs do tenant
O sistema SHALL manter a lista de CNPJs de um tenant. Cada entrada pode ser um CNPJ completo (14 posições) ou uma raiz de CNPJ (8 posições) que cobre todas as filiais. O sistema MUST aceitar CNPJ numérico e alfanumérico, validando os dígitos verificadores, e MUST impedir que o mesmo CNPJ ou raiz pertença a dois tenants.

#### Scenario: CNPJ alfanumérico válido
- **WHEN** um `TENANT_ADMIN` adiciona o CNPJ alfanumérico `12.ABC.345/01DE-35` ao seu tenant
- **THEN** o sistema valida os dígitos verificadores e registra o CNPJ

#### Scenario: Dígito verificador inválido
- **WHEN** um `TENANT_ADMIN` adiciona um CNPJ com dígito verificador inválido
- **THEN** o sistema responde 422 indicando o campo inválido

#### Scenario: CNPJ já pertencente a outro tenant
- **WHEN** um `TENANT_ADMIN` adiciona um CNPJ cuja raiz já está registrada em outro tenant
- **THEN** o sistema responde 409 e o CNPJ não é registrado

### Requirement: Autenticação de usuários via OIDC
O sistema SHALL exigir um access token JWT válido, emitido pelo provedor OIDC configurado, em todas as rotas `/api/**` e `/admin/**`. O tenant do usuário MUST ser obtido da claim `tenant_id` do token. As únicas exceções são os endpoints de webhook e os de health.

#### Scenario: Requisição sem token
- **WHEN** uma requisição sem header `Authorization` chega a `/api/v1/audits`
- **THEN** o sistema responde 401

#### Scenario: Token sem claim de tenant
- **WHEN** um token válido sem a claim `tenant_id` acessa uma rota `/api/**`
- **THEN** o sistema responde 403

#### Scenario: Token expirado ou com assinatura inválida
- **WHEN** a requisição apresenta um token expirado ou com assinatura que não confere com as chaves do provedor
- **THEN** o sistema responde 401

### Requirement: Papéis e permissões
O sistema SHALL autorizar operações conforme os papéis do token: `TENANT_ADMIN` (configuração, tabelas, políticas, credenciais), `AUDITOR` (aprovar, contestar, resolver, reabrir, reauditar), `FINANCE` (lotes de pagamento e baixa) e `VIEWER` (somente leitura). Qualquer papel do tenant MUST conseguir ler os dados do próprio tenant.

#### Scenario: Visualizador tenta aprovar
- **WHEN** um usuário que tem apenas o papel `VIEWER` tenta aprovar um caso de auditoria
- **THEN** o sistema responde 403 e o caso não muda

#### Scenario: Auditor tenta criar lote de pagamento
- **WHEN** um usuário que tem apenas o papel `AUDITOR` tenta criar um lote de pagamento
- **THEN** o sistema responde 403

### Requirement: Isolamento entre tenants
O sistema MUST garantir que nenhuma leitura, escrita, evento ou processamento assíncrono acesse dados de outro tenant. Um recurso de outro tenant MUST ser tratado como inexistente.

#### Scenario: Acesso a recurso de outro tenant
- **WHEN** um usuário do tenant A requisita `GET /api/v1/audits/{id}` com o id de um caso do tenant B
- **THEN** o sistema responde 404

#### Scenario: Listagem não vaza dados
- **WHEN** um usuário do tenant A lista casos de auditoria sem filtros
- **THEN** a resposta contém apenas casos do tenant A

#### Scenario: Processamento assíncrono preserva o tenant
- **WHEN** um documento do tenant A é processado por um consumidor assíncrono
- **THEN** todos os registros criados nesse processamento pertencem ao tenant A

### Requirement: Credenciais de integração
O sistema SHALL permitir que um `TENANT_ADMIN` crie, liste e revogue credenciais de integração do seu tenant, cada uma com um `keyId` público e um segredo. O segredo MUST ser exibido uma única vez, na criação, e nunca mais retornado por nenhuma API.

#### Scenario: Criação de credencial
- **WHEN** um `TENANT_ADMIN` cria uma credencial com a descrição "Webhook Intelipost"
- **THEN** o sistema responde 201 com `keyId` e segredo, e as listagens seguintes mostram só `keyId`, descrição, data de criação e status

#### Scenario: Revogação de credencial
- **WHEN** um `TENANT_ADMIN` revoga uma credencial
- **THEN** a credencial passa ao status `REVOKED` e as chamadas de webhook seguintes que a usam são rejeitadas

### Requirement: Autenticação de webhooks por assinatura HMAC
O sistema SHALL autenticar as chamadas a `/webhooks/**` pelos headers `X-EasyAudit-Key-Id`, `X-EasyAudit-Timestamp` (epoch em segundos) e `X-EasyAudit-Signature` (`sha256=` + HMAC-SHA256 hexadecimal de `"<timestamp>.<corpo bruto>"` com o segredo). O tenant MUST ser derivado da credencial. Timestamps fora de uma janela de 5 minutos MUST ser rejeitados.

#### Scenario: Assinatura válida
- **WHEN** um webhook chega com `keyId` ativo, timestamp dentro da janela e assinatura correta
- **THEN** a requisição é aceita e processada no tenant dono da credencial

#### Scenario: Assinatura inválida
- **WHEN** a assinatura não confere com o corpo recebido
- **THEN** o sistema responde 401 sem processar o corpo

#### Scenario: Timestamp fora da janela
- **WHEN** o timestamp tem mais de 5 minutos de diferença em relação ao relógio do servidor
- **THEN** o sistema responde 401

#### Scenario: Credencial revogada ou desconhecida
- **WHEN** o `keyId` não existe ou está revogado
- **THEN** o sistema responde 401
