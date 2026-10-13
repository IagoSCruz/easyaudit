# Spec Delta

## Purpose

Organizar os CT-es aprovados em lotes de pagamento por transportadora, exportar os lotes para o financeiro/ERP do lojista e registrar a baixa manual. A plataforma não movimenta dinheiro.

## ADDED Requirements

### Requirement: Criação de lote de pagamento
Um usuário `FINANCE` SHALL poder criar um lote informando a data de vencimento, uma descrição opcional e uma lista de casos `APPROVED` de uma mesma transportadora (mesma raiz de CNPJ). O lote MUST nascer `OPEN`, e os casos MUST ir para `SCHEDULED_FOR_PAYMENT`.

#### Scenario: Lote válido
- **WHEN** um usuário `FINANCE` cria um lote com 3 casos `APPROVED` da transportadora X e vencimento em 2026-11-10
- **THEN** o lote é criado `OPEN` com os totais de quantidade, valor cobrado, valor aprovado e economia, e os 3 casos ficam `SCHEDULED_FOR_PAYMENT`

#### Scenario: Caso não aprovado ou de outra transportadora
- **WHEN** a lista tem um caso `DIVERGENT` ou um caso de outra transportadora
- **THEN** o sistema responde 422, listando os casos inválidos, e nada é criado nem alterado

### Requirement: Manutenção de lote aberto
Um usuário `FINANCE` SHALL poder incluir casos `APPROVED` da mesma transportadora em um lote `OPEN` e retirar casos dele. Casos retirados MUST voltar para `APPROVED`, e os totais do lote MUST ser recalculados a cada mudança.

#### Scenario: Retirada de caso
- **WHEN** um usuário `FINANCE` retira 1 caso de um lote `OPEN` com 3 casos
- **THEN** o caso volta para `APPROVED` e o lote passa a ter 2 casos, com os totais recalculados

### Requirement: Exportação do lote
O sistema SHALL exportar um lote em CSV (UTF-8 com BOM, separador `;`, decimais com vírgula) com uma linha por CT-e: identificação do lote, CNPJ e nome da transportadora, chave, número, série e data de emissão do CT-e, valor cobrado, valor aprovado, glosa e vencimento. A exportação MUST registrar data e usuário.

#### Scenario: Exportação de lote aberto
- **WHEN** um usuário `FINANCE` exporta um lote com 2 casos (cobrado 103.00 e aprovado 100.00 no primeiro, e 66.02 e 66.02 no segundo)
- **THEN** o CSV tem cabeçalho e 2 linhas, com glosas "3,00" e "0,00", e a exportação fica registrada no lote

### Requirement: Baixa manual do lote
Um usuário `FINANCE` SHALL poder registrar o pagamento de um lote `OPEN` com ao menos um caso, informando a data do pagamento (não futura) e uma referência (por exemplo, o identificador do comprovante). O lote MUST ir para `PAID`, e todos os seus casos para `PAID`.

#### Scenario: Baixa registrada
- **WHEN** um usuário `FINANCE` registra o pagamento de um lote `OPEN` com data 2026-11-10 e referência "PIX-E2E-123"
- **THEN** o lote fica `PAID` com essa data e essa referência, e todos os casos ficam `PAID`

#### Scenario: Data futura
- **WHEN** a data de pagamento informada é posterior à data atual
- **THEN** o sistema responde 422

#### Scenario: Lote pago é imutável
- **WHEN** um usuário tenta incluir casos, retirar casos ou cancelar um lote `PAID`
- **THEN** o sistema responde 409

### Requirement: Cancelamento de lote
Um usuário `FINANCE` SHALL poder cancelar um lote `OPEN`. O lote MUST ir para `CANCELLED`, e seus casos MUST voltar para `APPROVED`.

#### Scenario: Cancelamento do lote
- **WHEN** um usuário `FINANCE` cancela um lote `OPEN` com 2 casos
- **THEN** o lote fica `CANCELLED` e os 2 casos voltam para `APPROVED`

### Requirement: Retirada automática de CT-e cancelado
Quando o caso de um lote `OPEN` é cancelado porque o CT-e foi cancelado ou substituído, o sistema SHALL retirar o caso do lote e recalcular os totais.

#### Scenario: CT-e cancelado dentro do lote
- **WHEN** chega o cancelamento do CT-e de um caso que está em um lote `OPEN`
- **THEN** o caso vai para `CANCELLED`, sai do lote, e os totais do lote são recalculados

### Requirement: Consulta de lotes
O sistema SHALL listar os lotes do tenant, com filtros por status, transportadora e período de vencimento, e expor o detalhe de cada lote com casos, totais, exportações e dados da baixa.

#### Scenario: Lotes em aberto
- **WHEN** um usuário lista os lotes com status `OPEN`
- **THEN** a resposta traz só os lotes em aberto do tenant, com os totais de cada um
